package mtr.data;
public abstract class AreaBase extends NameColorDataBase { public net.minecraft.util.Tuple<Integer, Integer> corner1, corner2; public boolean inArea(int x,int z){return false;} public net.minecraft.core.BlockPos getCenter(){return null;} public static boolean nonNullCorners(AreaBase a){return false;} }
