package org.by1337.bairx.timer;

import org.by1337.bairx.BAirDropX;
import org.by1337.bairx.airdrop.AirDrop;
import org.by1337.bairx.timer.strategy.TimerRegistry;
import org.by1337.bairx.util.Validate;
import org.by1337.blib.configuration.YamlContext;
import org.by1337.blib.util.NameKey;
import org.by1337.blib.util.Pair;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Waiter implements Timer {
    private final NameKey name;
    private final int tickSpeed;
    private final String timeZone;
    private final Map<NameKey, List<TimeWait>> timeWaits = new ConcurrentHashMap<>();
    private final Map<NameKey, AirDrop> activeAirdrops = new ConcurrentHashMap<>();
    private final Map<NameKey, Long> nextExecutionTimes = new ConcurrentHashMap<>();

    public Waiter(YamlContext context) {
        name = Validate.notNull(context.getAsNameKey("name"), "Параметр `name` не указан!");
        tickSpeed = Validate.notNull(context.getAsInteger("tick-speed"), "Параметр `tick-speed` не указан!");
        timeZone = Validate.notNull(context.getAsString("time-zone"), "Параметр `time-zone` не указан!");

        Map<String, Object> linkedAirdrops = context.getMap("linked-airdrops", Object.class, String.class);

        for (Map.Entry<String, Object> entry : linkedAirdrops.entrySet()) {
            NameKey airdropId = new NameKey(entry.getKey());
            Object value = entry.getValue();

            List<String> times = new ArrayList<>();
            if (value instanceof List) {
                for (Object timeObj : (List<?>) value) {
                    times.add(String.valueOf(timeObj));
                }
            } else if (value instanceof String) {
                times.add((String) value);
            }

            List<TimeWait> timeWaitList = new ArrayList<>();
            for (String time : times) {
                timeWaitList.add(new TimeWait(time, timeZone, airdropId));
            }

            timeWaits.put(airdropId, timeWaitList);
            calculateNextExecution(airdropId);
        }
    }

    @Override
    public NameKey name() {
        return name;
    }

    @Override
    public void tick(long currentTick) {
        if (currentTick % tickSpeed != 0) return;

        long currentTime = System.currentTimeMillis();

        Iterator<Map.Entry<NameKey, AirDrop>> activeIterator = activeAirdrops.entrySet().iterator();
        while (activeIterator.hasNext()) {
            Map.Entry<NameKey, AirDrop> entry = activeIterator.next();
            AirDrop airDrop = entry.getValue();

            if (airDrop.isStarted()) {
                airDrop.tick();
            } else {
                activeIterator.remove();
                calculateNextExecution(entry.getKey());
            }
        }

        for (Map.Entry<NameKey, Long> entry : nextExecutionTimes.entrySet()) {
            NameKey airdropId = entry.getKey();
            Long nextTime = entry.getValue();

            if (nextTime != null && currentTime >= nextTime) {
                startAirdrop(airdropId);
                calculateNextExecution(airdropId);
            }
        }
    }

    @Override
    public TimerRegistry getType() {
        return TimerRegistry.WAITER;
    }

    @Override
    public @Nullable Pair<AirDrop, Long> getNearest() {
        long currentTime = System.currentTimeMillis();
        Long nearestTime = null;
        AirDrop nearestAirDrop = null;

        for (Map.Entry<NameKey, Long> entry : nextExecutionTimes.entrySet()) {
            NameKey airdropId = entry.getKey();
            Long nextTime = entry.getValue();

            if (nextTime != null && nextTime > currentTime) {
                if (nearestTime == null || nextTime < nearestTime) {
                    nearestTime = nextTime;
                    nearestAirDrop = BAirDropX.getAirdropById(airdropId);
                }
            }
        }

        for (AirDrop airDrop : activeAirdrops.values()) {
            if (airDrop.isStarted()) {
                int toSpawnTicks = airDrop.getToSpawn();
                long timeToSpawn = ((long) toSpawnTicks) * 50L;
                if (nearestTime == null || timeToSpawn < (nearestTime - currentTime)) {
                    nearestTime = currentTime + timeToSpawn;
                    nearestAirDrop = airDrop;
                }
            }
        }

        if (nearestAirDrop != null && nearestTime != null) {
            return Pair.of(nearestAirDrop, nearestTime - currentTime);
        }

        return null;
    }

    private void startAirdrop(NameKey airdropId) {
        AirDrop airDrop = BAirDropX.getAirdropById(airdropId);
        if (airDrop != null && !airDrop.isStarted()) {
            activeAirdrops.put(airdropId, airDrop);
        } else if (airDrop == null) {
            BAirDropX.getMessage().warning(BAirDropX.translate("timer.ticker.unknown.airdrop"), name, airdropId);
        }
    }

    private void calculateNextExecution(NameKey airdropId) {
        List<TimeWait> timeWaitList = timeWaits.get(airdropId);
        if (timeWaitList == null || timeWaitList.isEmpty()) {
            nextExecutionTimes.put(airdropId, null);
            return;
        }

        long currentTime = System.currentTimeMillis();
        Long nextExecution = null;

        for (TimeWait timeWait : timeWaitList) {
            long executionTime = timeWait.getNextExecutionTime(currentTime);
            if (executionTime > currentTime && (nextExecution == null || executionTime < nextExecution)) {
                nextExecution = executionTime;
            }
        }

        if (nextExecution == null && !timeWaitList.isEmpty()) {
            nextExecution = timeWaitList.get(0).getNextExecutionTime(currentTime + 24 * 60 * 60 * 1000L);
        }

        nextExecutionTimes.put(airdropId, nextExecution);
    }

    private static class TimeWait {
        private final String timeString;
        private final String timeZone;
        private final NameKey airdropId;
        private final long timeInMillis;

        public TimeWait(String timeString, String timeZone, NameKey airdropId) {
            this.timeString = timeString;
            this.timeZone = timeZone;
            this.airdropId = airdropId;
            this.timeInMillis = parseTimeToMillis(timeString);
        }

        public long getNextExecutionTime(long fromTime) {
            Calendar calendar = Calendar.getInstance();
            TimeZone tz = TimeZone.getTimeZone(timeZone);
            calendar.setTimeZone(tz);
            calendar.setTimeInMillis(fromTime);

            calendar.set(Calendar.HOUR_OF_DAY, (int) (timeInMillis / (60 * 60 * 1000)));
            calendar.set(Calendar.MINUTE, (int) ((timeInMillis % (60 * 60 * 1000)) / (60 * 1000)));
            calendar.set(Calendar.SECOND, (int) ((timeInMillis % (60 * 1000)) / 1000));
            calendar.set(Calendar.MILLISECOND, 0);

            if (calendar.getTimeInMillis() <= fromTime) {
                calendar.add(Calendar.DAY_OF_YEAR, 1);
            }

            return calendar.getTimeInMillis();
        }

        private long parseTimeToMillis(String time) {
            String[] parts = time.split(":");
            long hours = 0, minutes = 0, seconds = 0;

            if (parts.length >= 1) hours = Long.parseLong(parts[0]);
            if (parts.length >= 2) minutes = Long.parseLong(parts[1]);
            if (parts.length >= 3) seconds = Long.parseLong(parts[2]);

            return (hours * 60 * 60 + minutes * 60 + seconds) * 1000L;
        }

        public String getTimeString() {
            return timeString;
        }

        public NameKey getAirdropId() {
            return airdropId;
        }
    }

    public Map<NameKey, List<TimeWait>> getTimeWaits() {
        return Collections.unmodifiableMap(timeWaits);
    }

    public Map<NameKey, AirDrop> getActiveAirdrops() {
        return Collections.unmodifiableMap(activeAirdrops);
    }

    public Map<NameKey, Long> getNextExecutionTimes() {
        return Collections.unmodifiableMap(nextExecutionTimes);
    }
}
