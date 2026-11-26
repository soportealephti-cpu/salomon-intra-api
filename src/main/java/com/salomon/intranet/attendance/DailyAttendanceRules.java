package com.salomon.intranet.attendance;

import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

/** Daily attendance policy, using the biometric clock's local time in Lima. */
public final class DailyAttendanceRules {
    private static final LocalTime LUNCH_START = LocalTime.of(12, 0);
    private static final LocalTime LUNCH_END = LocalTime.of(15, 0);

    private DailyAttendanceRules() {}

    public record Times(String entryTime, String exitTime, String lunchOutTime, String lunchReturnTime) {}

    private static boolean isLunch(LocalTime time) {
        return !time.isBefore(LUNCH_START) && !time.isAfter(LUNCH_END);
    }

    public static Times legacyClassify(List<String> rawTimes) {
        List<LocalTime> times = rawTimes.stream().filter(Objects::nonNull)
            .map(LocalTime::parse).distinct().sorted().toList();
        if (times.isEmpty()) return new Times(null, null, null, null);

        LocalTime entry = times.getFirst();
        LocalTime last = times.getLast();
        LocalTime exit = times.size() > 1 && !isLunch(last) ? last : null;
        List<LocalTime> lunch = times.stream()
            .filter(time -> time.isAfter(entry) && isLunch(time))
            .toList();
        LocalTime lunchOut = lunch.isEmpty() ? null : lunch.getFirst();
        LocalTime lunchReturn = lunch.size() < 2 ? null : lunch.getLast();
        return new Times(format(entry), format(exit), format(lunchOut), format(lunchReturn));
    }


    public static final java.time.LocalDate SEQUENCE_FROM=java.time.LocalDate.of(2026,11,26);
    public static final List<String> ORDER=List.of("ENTRY","LUNCH_OUT","LUNCH_RETURN","EXIT");
    public record Slot(String time,String sourceTime) {}
    public static Times classify(List<String> rawTimes) {return sequence(rawTimes,java.util.Map.of(),List.of());}
    public static Times sequence(List<String> rawTimes,java.util.Map<String,Slot> slots,List<String> excluded) {
        var ignored=new java.util.HashSet<LocalTime>();
        excluded.stream().filter(Objects::nonNull).map(LocalTime::parse).forEach(ignored::add);
        for(var slot:slots.values()){
            if(slot.sourceTime()!=null)ignored.add(LocalTime.parse(slot.sourceTime()));
            if(slot.time()!=null)ignored.add(LocalTime.parse(slot.time()));
        }
        var raw=rawTimes.stream().filter(Objects::nonNull).map(LocalTime::parse).distinct().sorted()
            .filter(t->!ignored.contains(t)).toList();
        LocalTime[] assigned=new LocalTime[4];
        int cursor=0;
        LocalTime previous=null;
        for(int i=0;i<ORDER.size();i++){
            Slot slot=slots.get(ORDER.get(i));
            if(slot!=null&&slot.time()!=null){
                LocalTime anchor=LocalTime.parse(slot.time());
                assigned[i]=anchor;
                while(cursor<raw.size()&&!raw.get(cursor).isAfter(anchor))cursor++;
                previous=anchor;
                continue;
            }
            LocalTime nextAnchor=null;
            for(int j=i+1;j<ORDER.size();j++){
                var future=slots.get(ORDER.get(j));
                if(future!=null&&future.time()!=null){nextAnchor=LocalTime.parse(future.time());break;}
            }
            while(cursor<raw.size()&&previous!=null&&raw.get(cursor).isBefore(previous))cursor++;
            if(cursor<raw.size()&&(nextAnchor==null||!raw.get(cursor).isAfter(nextAnchor))){
                assigned[i]=raw.get(cursor++);previous=assigned[i];
            }
        }
        return new Times(format(assigned[0]),format(assigned[3]),format(assigned[1]),format(assigned[2]));
    }

    private static String format(LocalTime time) {
        return time == null ? null : time.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
    }
}
