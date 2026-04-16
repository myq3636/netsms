package com.king.redis;

import java.util.List;
import java.util.Map;
import java.util.Set;










import redis.clients.jedis.JedisPoolConfig;

import com.king.framework.SystemLogger;
import com.king.gmms.GmmsUtility;

/**
 * Redis operate class.
 * 
 * 
 */
public class RedisClient {
	private static SystemLogger log = SystemLogger.getSystemLogger(RedisClient.class);
	private static RedisConnection redis = null;
	private static RedisClient init = new RedisClient();
	private boolean redisHaFlag = true;
	protected  JedisPoolConfig config = new JedisPoolConfig();	

	protected  String host = "192.168.23.112";
	protected  String host1 = "192.168.23.112";
	protected  int port = 6379;
	protected  int port1 = 6378;

		
	protected   int maxActive = 50;
	protected   int maxIdle = 50;
	protected  int minIdle = 30;
	protected  int maxWait = 20000;	
	protected  int minEvictableIdleTime = 180000;
	protected  int timeBetweenEvictionRuns = -1;
	protected  boolean isAuth = false;
	protected  String password = "";
	
	private RedisClient() {	
		try {			
			GmmsUtility gmmsUtility = GmmsUtility.getInstance();
			host = gmmsUtility.getCommonProperty("Redis_master_host");
			host1 = gmmsUtility.getCommonProperty("Redis_slave_host");
			port = Integer.parseInt(gmmsUtility.getCommonProperty("Redis_master_port","6379"));
			port1 = Integer.parseInt(gmmsUtility.getCommonProperty("Redis_slave_port","6379"));
			try {
				maxActive = Integer.parseInt(gmmsUtility.getCommonProperty("Redis_pool_maxActive","50"));
			} catch (Exception e) {
				maxActive = 50;
			}
			try {
				maxIdle = Integer.parseInt(gmmsUtility.getCommonProperty("Redis_pool_maxIdle","50"));
			} catch (Exception e) {
				maxIdle = 50;
			}
			try {
				minIdle = Integer.parseInt(gmmsUtility.getCommonProperty("Redis_pool_minIdle","30"));
			} catch (Exception e) {
				minIdle = 30;
			}
			try {
				maxWait = Integer.parseInt(gmmsUtility.getCommonProperty("Redis_pool_maxWait","20000"));
			} catch (Exception e) {
				maxWait = 20000;
			}
			try {
				minEvictableIdleTime = Integer.parseInt(gmmsUtility.getCommonProperty("Redis_pool_minEvictableIdleTime","180000"));
			} catch (Exception e) {
				minEvictableIdleTime = 180000;
			}
			try {
				timeBetweenEvictionRuns = Integer.parseInt(gmmsUtility.getCommonProperty("Redis_pool_timeBetweenEvictionRuns","-1"));
			} catch (Exception e) {
				timeBetweenEvictionRuns = -1;
			}
			if(config==null){
				config = new JedisPoolConfig();
			}
			isAuth = Boolean.parseBoolean(gmmsUtility.getCommonProperty("Redis_isAuth","false"));
			password = gmmsUtility.getCommonProperty("Redis_password",null);
			config.setEvictionPolicyClassName("org.apache.commons.pool2.impl.DefaultEvictionPolicy");
            // 是否启用pool的jmx管理功能, 默认true
            config.setJmxEnabled(true);
            config.setMaxTotal(maxActive);
			config.setMaxIdle(maxIdle);
			config.setMinIdle(minIdle);	
			//config.timeBetweenEvictionRunsMillis = -1;			
			config.setMinEvictableIdleTimeMillis(minEvictableIdleTime);
			config.setTimeBetweenEvictionRunsMillis(timeBetweenEvictionRuns);			
			config.setTestOnReturn(false);
			config.setTestOnBorrow(false);
			config.setTestWhileIdle(true);
								
		} catch (Exception e) {
			log.error("Redis config init error!", e);
		}			
	}

	
	public static RedisClient getInstance() {
		return init;
	}
	
	public boolean setString(String key, String obj) {
		return redis.setString(key, obj);
	}
	
	public String setnxString(String key, String value, int expireSeconds) {
		return redis.setnxString(key, value, expireSeconds);
	}
	
	public Long incBlackhole(String key) {
		return redis.incblackhole(key);
	}
	
	public Long incrString(String key) {
		return redis.incrString(key);
	}

	public void setExpire(String key, int time) {
		redis.setExpired(key, time);
	}
	
	public boolean setString(String key, String obj,int time) {
		return redis.setString(key, obj, time);
	}
	
	public boolean lpush(String key, String obj) {
		return redis.lpush(key, obj);
	}

	public boolean setPipeline(String key, String value, int expireTime, String hashKey ){
		return redis.setPipeline(key, value, expireTime, hashKey);
	}
	
	public boolean setDelayDR(String key, String value, int expireTime, double score, int ossid){
		return redis.setDelayDR(key, value, expireTime, score, ossid);
	}
	
	public Set<String> zrange(String key, double start, double end){
		return redis.zrange(key, start, end);
	}
	
	public Set<String> zrangeByIndex(String key, long start, long end){
		return redis.zrangeByIndex(key, start, end);
	}
	
	public boolean zrem(String key, String[] members){
		return redis.zrem(key, members);
	}
	
	public boolean setRoutingInfo(String keyword, Map<String, Map<String, String>> infos){
		return redis.setHashMapWithPipline(keyword, infos);
	}
	
	public boolean setContentTemplate(String keyword, Map<String, Map<String, String>> infos){
		return redis.setHashMapWithPipline(keyword, infos);
	}
	
	public void delPipeline(String key,String hashKey){
		  redis.delPipeline(key,hashKey);
	}

	public String getString(String key) {
		return redis.getString(key);
	}
	
	public boolean hashINC(String key, String field) {
		return redis.hashINC(key, field);
	}
	
	public String rpop(String key) {
		return redis.rpop(key);
	}
	
	/**
	 * Batch-pop up to count elements from the list in one Redis round-trip.
	 * Dramatically faster than calling rpop() count times.
	 */
	public List<String> rpopBatch(String key, int count) {
		return redis.rpopBatch(key, count);
	}
	
	public String brpop(String key) {
		return redis.brpop(key);
	}
	
	public boolean lpush(String setKey, String key, String[] values) {
		return redis.lpushArr(setKey, key, values);
	}

	public boolean setHash(String key, Map<String,String>map) {
		return redis.setHash(key, map);
	}
	
	public boolean setHashAndSet(String setKey, String key, Map<String,String>map) {
		return redis.setHashAndSet(setKey, key, map);
	}

	public Set<String> getHash(String key) {
		Set<String> set = redis.getHashValue(key);
		redis.delObject(key);
		return set;
	}
	
	public Map<String,String> getHashAll(String key) {			
		return redis.getHashAll(key);
	}

	public List<String> getHashMap(String key,String[] s) throws Exception {
		List<String> list = redis.getHashMap(key,s);		
		return list;
	}
	
	public void del(String key) {
		redis.delObject(key);
	}
	
	public void delHash(String key,String field) {
		redis.delHash(key, field);
	}

	public boolean isRedisHaFlag() {
		return redisHaFlag;
	}

	public void setRedisHaFlag(boolean redisHaFlag) {
		this.redisHaFlag = redisHaFlag;
		RedisConnection oldRedis = redis;
        if(redisHaFlag){        	
        	redis = new RedisConnection(config,host,port,isAuth,password);        	        	
        }else{
        	redis = new RedisConnection(config,host1,port1,isAuth,password);
        }
        if(oldRedis!=null){
        	oldRedis.destroy();
        }
        
	}
	
	public boolean addStock(String key, long timemark, long num){
		return redis.addStock(key, timemark, num);
	}
	
	public Long stock(String key, int num){
		return redis.stock(key, num);
	}
	public Set<String> smembers(String key){
		return redis.smembers(key);
	}
	
	public boolean sadd(String key,List<String> values){
		return redis.sadd(key, values);
	}
}
