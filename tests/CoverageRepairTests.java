import java.util.*;

public class CoverageRepairTests {
    private static int checks;
    private static void check(boolean ok,String message) {
        checks++; if(!ok) throw new AssertionError(message);
    }
    private static CoverageRepair.Worker worker(int id,String type,int start,int end,int... positions) {
        var w=new CoverageRepair.Worker(id,type);
        Arrays.fill(w.available,start,end,true); w.requestedSlots=end-start;
        for(int p:positions) w.positions.add(p);
        return w;
    }
    private static void assign(CoverageRepair.Worker w,int start,int end,int p) {
        Arrays.fill(w.assigned,start,end,p);
    }
    private static Map<Integer,int[]> needs(int start,int end) {
        Map<Integer,int[]> m=new LinkedHashMap<>();
        for(int p:new int[]{2,1}) { int[] a=new int[24]; Arrays.fill(a,start,end,1); m.put(p,a); }
        return m;
    }
    public static void main(String[] args) {
        // October 9: manager finishes at 18, another worker at 18:30, one closes alone.
        var manager=worker(1,"FULL_TIME",0,24,1,2); assign(manager,0,16,2);
        var early=worker(2,"PART_TIME",1,17,1,2); assign(early,1,16,1); assign(early,16,17,2);
        var closer=worker(3,"PART_TIME",3,24,1,2); assign(closer,16,24,1);
        var relief=worker(4,"PART_TIME",16,24,1,2);
        var team=List.of(manager,early,closer,relief);
        var additions=CoverageRepair.repair(team,needs(16,24));
        check(additions.size()==8,"Reserve four hours, starting before the 18:30 shortage");
        check(additions.stream().allMatch(a->a.employee()==4 && a.position()==2),"Prefer qualified relief over extending manager");
        check(relief.assigned[16]!=null && relief.assigned[23]!=null,"Relief works 18-22");
        check(manager.assigned[16]==null,"Manager may finish once relief is secured");
        check(CoverageRepair.repair(team,needs(16,24)).isEmpty(),"Repeat repair adds no duplicate assignments");

        manager=worker(1,"FULL_TIME",0,24,1,2); assign(manager,0,16,2);
        closer=worker(3,"PART_TIME",16,24,1); assign(closer,16,24,1);
        additions=CoverageRepair.repair(List.of(manager,closer),needs(17,24));
        check(additions.size()==8,"Bridge 18-18:30 gap when manager must continue");
        check(manager.assigned[16]!=null && manager.assigned[23]!=null,"Manager continues through closing without a gap");

        var absent=worker(4,"PART_TIME",16,24,2); absent.available[18]=false;
        closer=worker(3,"PART_TIME",16,24,1); assign(closer,16,24,1);
        check(CoverageRepair.repair(List.of(absent,closer),needs(17,24)).isEmpty(),"Do not bridge a requested time off or shorten minimum shift");
        var newbie=worker(5,"NEWBIE",16,22,2); assign(newbie,16,22,2);
        check(CoverageRepair.repair(List.of(newbie,closer),needs(16,24)).isEmpty(),"Do not extend newbie block or work past 21");
        var hallOnly=worker(6,"PART_TIME",16,24,1);
        check(CoverageRepair.repair(List.of(hallOnly,closer),needs(16,24)).isEmpty(),"No unqualified kitchen assignment");
        var shortRequest=worker(7,"PART_TIME",17,24,2);
        additions=CoverageRepair.repair(List.of(shortRequest,closer),needs(17,24));
        check(additions.size()==7,"A genuinely shorter request retains existing minimum-hours exception");
        check(shortRequest.assigned[16]==null,"Never start before requested availability");
        var noStaff=CoverageRepair.repair(List.of(),needs(0,1));
        check(noStaff.isEmpty(),"Real headcount shortage remains unfilled");
        System.out.println("PASS: "+checks+" coverage repair checks (no database)");
    }
}
