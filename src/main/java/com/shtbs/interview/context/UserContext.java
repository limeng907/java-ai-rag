package com.shtbs.interview.context;

public class UserContext {
    private static final ThreadLocal<Long> USER_HOLDER
            = new ThreadLocal<>();

    public static void setUserId(Long userid){
        USER_HOLDER.set(userid);
    }

    public static Long getUserId(){
        return USER_HOLDER.get();
    }

    public static void remove(){
        USER_HOLDER.remove();
    }

}
