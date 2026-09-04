package com.xuan.boot.support;

public final class RedisKeys {
    public static final String LOGIN_TOKEN = "login:token:";
    public static final String CLASSROOM_CACHE = "cache:classroom:";
    public static final String CLASSROOM_SEARCH_CACHE = "cache:classroom:search:";
    public static final String ADVISOR_CACHE = "cache:advisor:";
    public static final String REDIS_OVERVIEW_CACHE = "cache:ops:redis:overview";
    public static final String RESERVE_STOCK = "reserve:stock:";
    public static final String RESERVE_USERS = "reserve:users:";
    public static final String RESERVE_USER_TIME = "reserve:user-time:";
    public static final String SUBMIT_TOKEN = "reserve:submit:";
    public static final String HOT_ROOM_RANK = "rank:room:hot";
    public static final String USER_SIGN = "sign:user:";
    public static final String ID_COUNTER = "icr:";
    public static final String AGENT_SESSION = "agent:session:";

    private RedisKeys() {
    }
}
