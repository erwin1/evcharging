package evcharging.impl.sma;

import evcharging.services.ElectricityMeter;
import evcharging.services.MeterReading;
import evcharging.services.MeterData;
import io.quarkus.arc.lookup.LookupIfProperty;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import java.io.*;
import java.net.URLConnection;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SMA implementation of the ElectricityMeter interface.
 * It uses a reverse engineered version of the SMA inverter's local web admin UI .
 */
@LookupIfProperty(name = "evcharging.meter", stringValue = "sma", lookupIfMissing = false)
@ApplicationScoped
public class SMAInverter implements ElectricityMeter {
    public static final Logger LOGGER = Logger.getLogger(SMAInverter.class.getName());
    @ConfigProperty(name = "EVCHARGING_INVERTER_IP")
    String inverterIp;
    @ConfigProperty(name = "EVCHARGING_INVERTER_PASSWORD")
    String inverterPassword;
    @ConfigProperty(name = "evcharging.meter", defaultValue = "hwep1")
    String meter;

    String sid;

    MeterReading currentMonth15minUsagePeak;

    void onStart(@Observes StartupEvent ev) {
        if ("sma".equals(meter)) {
            checkCurrentMonth15minPeak();
        }
    }

    @PreDestroy
    public void preDestroy() {
        LOGGER.info("logging out SMA inverter");
        logout();
    }

    @Override
    public MeterData getCurrentData() {
        SMAValues smaValues = getCurrentSMAValues();

        ZonedDateTime startOfPeriod = ZonedDateTime.now();
        startOfPeriod = startOfPeriod.minusMinutes(startOfPeriod.getMinute() % 15).withSecond(0).withNano(0);

        long outAtStartOfPeriod = getConsumptionMeterReadingAt(startOfPeriod);
        long out = getCurrentConsumptionMeterReading();

        int avg = (int) ((out - outAtStartOfPeriod) * 4);

        return new MeterData(smaValues.getFromGrid() - smaValues.getToGrid(), avg, out);
    }

    @Override
    public MeterReading getCurrentMonthPeak() {
        return currentMonth15minUsagePeak;
    }

    @Scheduled(cron="0 20 0 * * ?")
    public void checkCurrentMonth15minPeak() {
        ZonedDateTime startTime = ZonedDateTime.now().withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        ZonedDateTime endTime = ZonedDateTime.now();
        MeterReading highestReading = new MeterReading(startTime, 0);

        while(startTime.isBefore(endTime)) {
            List<MeterReading> readings = getFromGridUsagePer15minBetween(startTime, startTime.plusDays(1));
            for (MeterReading reading : readings) {
                if (reading.getValue() > highestReading.getValue()) {
                    highestReading = reading;
                }
            }
            startTime = startTime.plusDays(1);
        }
        if (highestReading.getValue() != 0) {
            currentMonth15minUsagePeak = highestReading;
            currentMonth15minUsagePeak.setValue(currentMonth15minUsagePeak.getValue() * 4);
        }
        if (currentMonth15minUsagePeak != null) {
            LOGGER.log(Level.INFO, "current month 15min peak = {0} at {1}", new Object[]{currentMonth15minUsagePeak.getValue(), currentMonth15minUsagePeak.getTimestamp()});
        } else {
            LOGGER.log(Level.INFO, "no known current month 15min peak");
        }
    }

    SMAValues getCurrentSMAValues() {
        try {
            return readInternal();
        } catch (SMAAuthException e) {
            sid = null;
            try {
                return readInternal();
            } catch (SMAAuthException ex) {
                throw new RuntimeException(ex.toString());
            }
        }
    }

    long getConsumptionMeterReadingAt(ZonedDateTime timestamp) {
        if (ZonedDateTime.now().toEpochSecond() - timestamp.toEpochSecond() <= 5) {
            LOGGER.fine("Waiting 5s to get a better reading");
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        try {
            return getAbsoluteFromGridMeterReadingAtTimestampInternal(timestamp);
        } catch (SMAAuthException e) {
            sid = null;
            try {
                return getAbsoluteFromGridMeterReadingAtTimestampInternal(timestamp);
            } catch (SMAAuthException ex) {
                throw new RuntimeException(ex.toString());
            }
        }
    }

    long getCurrentConsumptionMeterReading() {
        try {
            return getAbsoluteFromGridMeterReadingInternal();
        } catch (SMAAuthException e) {
            sid = null;
            try {
                return getAbsoluteFromGridMeterReadingInternal();
            } catch (SMAAuthException ex) {
                throw new RuntimeException(ex.toString());
            }
        }
    }

    List<MeterReading> getFromGridUsagePer15minBetween(ZonedDateTime startTime, ZonedDateTime endTime) {
        try {
            return getFromGridUsagePer15minBetweenInternal(startTime, endTime);
        } catch (SMAAuthException e) {
            sid = null;
            try {
                return getFromGridUsagePer15minBetweenInternal(startTime, endTime);
            } catch (SMAAuthException ex) {
                throw new RuntimeException(ex.toString());
            }
        }
    }
    List<MeterReading> getFromGridUsagePer15minBetweenInternal(ZonedDateTime startTime, ZonedDateTime endTime) throws SMAAuthException {
        return getMeterReadingsPer15minBetween("28736", startTime, endTime);
    }

    List<MeterReading> getMeterReadingsPVPer15minBetween(ZonedDateTime startTime, ZonedDateTime endTime) throws SMAAuthException {
        return getMeterReadingsPer15minBetween("28672", startTime, endTime);
    }

    List<MeterReading> getMeterReadingsPer15minBetween(String key, ZonedDateTime startTime, ZonedDateTime endTime) throws SMAAuthException {
        try {
            if (sid == null) {
                sid = authenticate();
            }
            URLConnection conn = HttpHelper.openConnection("https://"+ inverterIp +"/dyn/getLogger.json?sid="+sid);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");

            long start = startTime.toEpochSecond();
            long end = endTime.toEpochSecond();

            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(conn.getOutputStream()));
            writer.write("{\"destDev\":[],\"key\":"+key+",\"tStart\":"+start+",\"tEnd\":"+end+"}");
            writer.flush();

            String body = new String(conn.getInputStream().readAllBytes());
            conn.getInputStream().close();

//            System.out.println("BODY = "+body);

            JsonObject object = new JsonObject(body);

            if (object.getString("err") != null) {
                throw new evcharging.impl.sma.SMAAuthException();
            }

            JsonArray array = object.getJsonObject("result").getJsonArray("0199-xxxxx04E");
            int startValue = 0;
            List<MeterReading> list = new LinkedList<>();
            for(int i=0;i<array.size();i++) {
                long time = array.getJsonObject(i).getLong("t");
                String value = array.getJsonObject(i).getString("v", "0");
                int intValue = 0;
                if (value != null) {
                    intValue = Integer.parseInt(value);
                }
                ZonedDateTime ts = ZonedDateTime.ofInstant(Instant.ofEpochSecond(time), ZoneId.of("UTC"));

                if (ts.getMinute() % 15 == 0) {
                    if (startValue == 0) {
                        startValue = intValue;
                    } else {
                        int diff = intValue - startValue;
                        list.add(new MeterReading(ts, diff));
                        startValue = intValue;
                    }
                }
            }
            return list;
        } catch (SMAAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    long getAbsoluteFromGridMeterReadingAtTimestampInternal(ZonedDateTime timestamp) throws SMAAuthException {
        try {
            if (sid == null) {
                sid = authenticate();
            }
            URLConnection conn = HttpHelper.openConnection("https://"+ inverterIp +"/dyn/getLogger.json?sid="+sid);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");

            long start = timestamp.toEpochSecond();
            long end = start;

            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(conn.getOutputStream()));
            writer.write("{\"destDev\":[],\"key\":28736,\"tStart\":"+start+",\"tEnd\":"+end+"}");
            writer.flush();

            String body = new String(conn.getInputStream().readAllBytes());
            conn.getInputStream().close();

//            System.out.println("BODY = "+body);

            JsonObject object = new JsonObject(body);

            if (object.getString("err") != null) {
                throw new evcharging.impl.sma.SMAAuthException();
            }
            return object.getJsonObject("result").getJsonArray("0199-xxxxx04E").getJsonObject(0).getInteger("v");

        } catch (SMAAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    long getAbsoluteFromGridMeterReadingInternal() throws SMAAuthException {
        try {
            if (sid == null) {
                sid = authenticate();
            }
            URLConnection conn = HttpHelper.openConnection("https://"+ inverterIp +"/dyn/getAllOnlValues.json?sid="+sid);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");

            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(conn.getOutputStream()));
            writer.write("{\"destDev\":[]}");
            writer.flush();

            String body = new String(conn.getInputStream().readAllBytes());
            conn.getInputStream().close();

//            System.out.println("BODY = "+body);

            JsonObject object = new JsonObject(body);

            if (object.getString("err") != null) {
                throw new evcharging.impl.sma.SMAAuthException();
            }

            long reading = object.getJsonObject("result")
                    .getJsonObject("0199-xxxxx04E")
                    .getJsonObject("6400_00469200")
                    .getJsonArray("1")
                    .getJsonObject(0)
                    .getLong("val", 0L);
            return reading;

        } catch (SMAAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public SMAValues readInternal() throws SMAAuthException {
        try {
            if (sid == null) {
                sid = authenticate();
            }
            URLConnection conn = HttpHelper.openConnection("https://"+ inverterIp +"/dyn/getAllOnlValues.json?sid="+sid);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");

            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(conn.getOutputStream()));
            writer.write("{\"destDev\":[]}");
            writer.flush();

            String body = new String(conn.getInputStream().readAllBytes());
            conn.getInputStream().close();

//            System.out.println("BODY = "+body);

            JsonObject object = new JsonObject(body);

            if (object.getString("err") != null) {
                throw new evcharging.impl.sma.SMAAuthException();
            }

            int fromGrid = object.getJsonObject("result")
                    .getJsonObject("0199-xxxxx04E")
                    .getJsonObject("6100_40463700")
                    .getJsonArray("1")
                    .getJsonObject(0)
                    .getInteger("val", 0);
            int toGrid = object.getJsonObject("result")
                    .getJsonObject("0199-xxxxx04E")
                    .getJsonObject("6100_40463600")
                    .getJsonArray("1")
                    .getJsonObject(0)
                    .getInteger("val", 0);
            String x = object.getJsonObject("result")
                    .getJsonObject("0199-xxxxx04E")
                    .getJsonObject("6100_40263F00")
                    .getJsonArray("1")
                    .getJsonObject(0)
                    .getString("val");
            int fromPV = 0;
            if (x != null) {
                fromPV = Integer.parseInt(x);
            }

            return new SMAValues(toGrid, fromGrid, fromPV, fromGrid+fromPV-toGrid);

        } catch (SMAAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String authenticate() {
        try {
            URLConnection conn = HttpHelper.openConnection("https://"+ inverterIp +"/dyn/login.json");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");

            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(conn.getOutputStream()));
            writer.write("{\"right\":\"usr\",\"pass\":\""+ inverterPassword +"\"}");
            writer.close();
            String body = new String(conn.getInputStream().readAllBytes());
            conn.getInputStream().close();
//            System.out.println("BODY = "+body);

            JsonObject object = new JsonObject(body);
            if (object.getInteger("err") != null) {
                throw new RuntimeException("auth err "+object.getInteger("err"));
            }
            String sid = object.getJsonObject("result").getString("sid");
            LOGGER.info("successfully retrieved SMA sid");
            return sid;

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void logout() {
        if (sid != null) {
            try {
                URLConnection conn = HttpHelper.openConnection("https://"+ inverterIp +"/dyn/logout.json?sid=" + sid);
                conn.setRequestProperty("Accept", "application/json");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json");

                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(conn.getOutputStream()));
                writer.write("{}");
                writer.flush();

                BufferedInputStream reader = new BufferedInputStream(conn.getInputStream());
                LOGGER.info("logout response = "+new String(reader.readAllBytes()));

                sid = null;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

}
