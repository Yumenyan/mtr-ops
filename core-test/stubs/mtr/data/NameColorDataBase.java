package mtr.data;
public abstract class NameColorDataBase extends SerializedDataBase implements Comparable<NameColorDataBase> { public final long id=0; public final TransportMode transportMode=null; public String name; public int color; public boolean isTransportMode(TransportMode m){return false;} public int compareTo(NameColorDataBase o){return 0;} }
