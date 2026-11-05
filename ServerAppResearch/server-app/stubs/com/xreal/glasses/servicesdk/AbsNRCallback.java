package com.xreal.glasses.servicesdk;
/** Compile-only ABI declaration; runtime uses the real vendor DEX class. */
public abstract class AbsNRCallback {
    public void onGlassesAction(int type,int p1,int p2,long time) {}
    public void onGlassesEvent(int category,int event,int offset,int p1,int p2,byte[] description) {}
    public void onClientCountChanged(int running,int paused) {}
    public void onServiceMethodError(int code) {}
}
