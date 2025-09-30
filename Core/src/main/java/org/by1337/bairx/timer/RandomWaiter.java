package org.by1337.bairx.timer;

import org.by1337.bairx.BAirDropX;
import org.by1337.bairx.airdrop.AirDrop;
import org.by1337.bairx.random.WeightedAirDrop;
import org.by1337.bairx.random.WeightedRandomItemSelector;
import org.by1337.bairx.timer.strategy.TimerRegistry;
import org.by1337.bairx.util.Validate;
import org.by1337.blib.configuration.YamlContext;
import org.by1337.blib.util.NameKey;
import org.by1337.blib.util.Pair;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class RandomWaiter implements Timer {
    private final NameKey name;
    private final int tickSpeed;
    private final String timeZone;
    private final List<TimeWait> timeWaits = new ArrayList<>();
    private final Map<NameKey, AirDrop> activeAirdrops = new ConcurrentHashMap<>();
    private final Map<NameKey, Long> nextExecutionTimes = new ConcurrentHashMap<>();

    private final List<WeightedAirDrop> weightedAirdrops = new ArrayList<>();
    private final WeightedRandomItemSelector<WeightedAirDrop> randomSelector;
    private final Set<NameKey> linkedAirDrops = new HashSet<>();

    public RandomWaiter(YamlContext context) {
        name = Validate.notNull(context.getAsNameKey("name"), "Параметр `name` не указан!");
        tickSpeed = Validate.notNull(context.getAsInteger("tick-speed"), "Параметр `tick-speed` не указан!");
        timeZone = Validate.notNull(context.getAsString("time-zone"), "Параметр `time-zone` не указан!");

        List<String> times = context.getList("appearance-times", String.class);
        if (times == null || times.isEmpty()) {
            throw new IllegalArgumentException("Параметр `appearance-times` не указан или пуст!");
        }

        for (String time : times) {
            timeWaits.add(new TimeWait(time, timeZone));
        }

        Map<String, Object> weightedAirdropsConfig = context.getMap("weighted-airdrops", Object.class, String.class);
        if (weightedAirdropsConfig == null || weightedAirdropsConfig.isEmpty()) {
            throw new IllegalArgumentException("Параметр `weighted-airdrops` не указан или пуст!");
        }

        for (Map.Entry<String, Object> entry : weightedAirdropsConfig.entrySet()) {
            NameKey airdropId = new NameKey(entry.getKey());
            Object value = entry.getValue();

            int weight = Validate.tryMap(value,
                    obj -> Integer.parseInt(String.valueOf(obj)),
                    "Вес аирдропа должен быть числом",
                    value
            );

            weightedAirdrops.add(new WeightedAirDrop(airdropId, weight));
            linkedAirDrops.add(airdropId);
        }

        randomSelector = new WeightedRandomItemSelector<>(weightedAirdrops);
        calculateNextExecution();
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
            }
        }

        for (Map.Entry<NameKey, Long> entry : nextExecutionTimes.entrySet()) {
            NameKey timeSlotId = entry.getKey();
            Long nextTime = entry.getValue();

            if (nextTime != null && currentTime >= nextTime) {
                startRandomAirdrop(timeSlotId);
                calculateNextExecutionForTimeSlot(timeSlotId);
            }
        }
    }

    @Override
    public TimerRegistry getType() {
        return TimerRegistry.RANDOM_WAITER;
    }

    @Override
    public @Nullable Pair<AirDrop, Long> getNearest() {
        long currentTime = System.currentTimeMillis();
        Long nearestTime = null;
        AirDrop nearestAirDrop = null;

        for (Map.Entry<NameKey, Long> entry : nextExecutionTimes.entrySet()) {
            Long nextTime = entry.getValue();

            if (nextTime != null && nextTime > currentTime) {
                if (nearestTime == null || nextTime < nearestTime) {
                    nearestTime = nextTime;
                    if (!weightedAirdrops.isEmpty()) {
                        nearestAirDrop = BAirDropX.getAirdropById(weightedAirdrops.get(0).getId());
                    }
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

    private void startRandomAirdrop(NameKey timeSlotId) {
        WeightedAirDrop selected = randomSelector.getRandomItem();
        if (selected != null) {
            AirDrop airDrop = BAirDropX.getAirdropById(selected.getId());
            if (airDrop != null && !airDrop.isStarted()) {
                activeAirdrops.put(selected.getId(), airDrop);
                airDrop.forceStart(null, null);
            } else if (airDrop == null) {
                BAirDropX.getMessage().warning(BAirDropX.translate("timer.ticker.unknown.airdrop"), name, selected.getId());
            }
        }
    }

    private void calculateNextExecution() {
        nextExecutionTimes.clear();

        for (int i = 0; i < timeWaits.size(); i++) {
            NameKey timeSlotId = new NameKey("time_slot_" + i);
            calculateNextExecutionForTimeSlot(timeSlotId);
        }
    }

    private void calculateNextExecutionForTimeSlot(NameKey timeSlotId) {
        int timeSlotIndex = extractTimeSlotIndex(timeSlotId);
        if (timeSlotIndex < 0 || timeSlotIndex >= timeWaits.size()) {
            return;
        }

        TimeWait timeWait = timeWaits.get(timeSlotIndex);
        long currentTime = System.currentTimeMillis();
        long nextExecution = timeWait.getNextExecutionTime(currentTime);

        nextExecutionTimes.put(timeSlotId, nextExecution);
    }

    private int extractTimeSlotIndex(NameKey timeSlotId) {
        try {
            String name = timeSlotId.getName();
            if (name.startsWith("time_slot_")) {
                return Integer.parseInt(name.substring("time_slot_".length()));
            }
        } catch (NumberFormatException e) {
            // ignore
        }
        return -1;
    }

    private static class TimeWait {
        private final String timeString;
        private final String timeZone;
        private final long timeInMillis;

        public TimeWait(String timeString, String timeZone) {
            this.timeString = timeString;
            this.timeZone = timeZone;
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
    }

    public List<WeightedAirDrop> getWeightedAirdrops() {
        return Collections.unmodifiableList(weightedAirdrops);
    }

    public List<TimeWait> getTimeWaits() {
        return Collections.unmodifiableList(timeWaits);
    }

    public Map<NameKey, AirDrop> getActiveAirdrops() {
        return Collections.unmodifiableMap(activeAirdrops);
    }

    public Map<NameKey, Long> getNextExecutionTimes() {
        return Collections.unmodifiableMap(nextExecutionTimes);
    }

    public Set<NameKey> getLinkedAirDrops() {
        return Collections.unmodifiableSet(linkedAirDrops);
    }
}
