package net.minecraft.client.gui.components;
public class Button extends net.minecraft.client.gui.components.AbstractWidget { public Button(int x,int y,int w,int h, net.minecraft.network.chat.Component m, OnPress p){super(x,y,w,h,m);} public interface OnPress { void onPress(Button b); } }
