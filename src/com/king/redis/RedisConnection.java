package com.king.redis;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.Response;

import com.king.framework.SystemLogger;

/**
 * Redis connection wrapper using JedisPool.
 * 
 * All methods use try-with-resources to ensure Jedis instances are
 * always returned to the pool exactly once, even on exceptions.
 */
public class RedisConnection {

	private static final SystemLogger logger = SystemLogger
			.getSystemLogger(RedisConnection.class);

	/**
	 * redis pool
	 */
	protected JedisPool pool;
	/**
	 * 执行扣库存的脚本
	 */
	public static final String STOCK_LUA;

	static {
		/**
		 *
		 * @desc 扣减库存Lua脚本  库存（stock）0：表示没有库存
		 *       库存（stock）大于0：表示剩余库存
		 *库存（stock）-1：表示不限库存去掉
		 *sb.append("    if (stock == -1) then");
		 *sb.append("        return -1;");
		 *sb.append("    end;");
		 * @params 库存key
		 * @return -3:库存未初始化 -2:库存不足 ; 大于等于0:剩余库存（扣减之后剩余的库存）
		 *         redis缓存的库存(value)是-1表示不限库存，直接返回1
		 */
		StringBuilder sb = new StringBuilder();
		sb.append("if (redis.call('exists', KEYS[1]) == 1) then");
		sb.append("    local stock = tonumber(redis.call('get', KEYS[1]));");
		sb.append("    local num = tonumber(ARGV[1]);");
		sb.append("    if (stock >= num) then");
		sb.append("        return redis.call('incrby', KEYS[1], 0 - num);");
		sb.append("    end;");
		sb.append("    return -2;");
		sb.append("end;");
		sb.append("return -3;");
		STOCK_LUA = sb.toString();
	}

	public RedisConnection(JedisPoolConfig config, String host, int port,
			boolean isAuth, String pwd) {
		if (isAuth) {
			pool = new JedisPool(config, host, port, Protocol.DEFAULT_TIMEOUT,
					pwd);
		} else {
			
			pool = new JedisPool(config, host, port);
		}
	}

	/**
	 * get redis from redis pool
	 */
	public Jedis getJedis() {

		if (pool == null) {
			return null;
		}
		Jedis jedis = null;
		try {
			jedis = pool.getResource();
		} catch (Exception e) {
			logger.error("get redis error !");
		}
		return jedis;
	}

	public void destroy() {
		if (pool == null) {
			return;
		}
		try {
			pool.destroy();
		} catch (Exception e) {
			logger.error("Fail to close the Jedis pool, and exception is {}",
					e.getMessage());
		}
	}

	/**
	 * set object
	 */
	public boolean setObject(String key, Object obj) {
		try (Jedis jedis = pool.getResource()) {
			String result = jedis.set(key, SerializableHandler.objectToString(obj));
			return "OK".equalsIgnoreCase(result);
		} catch (Exception e) {
			logger.error("setObject failed. key={}", key, e);
			return false;
		}
	}

	public boolean setString(String key, String obj) {
		try (Jedis jedis = pool.getResource()) {
			String result = jedis.set(key, obj);
			return "OK".equalsIgnoreCase(result);
		} catch (Exception e) {
			logger.error("setString failed. key={}", key, e);
			return false;
		}
	}
	
	public Long incblackhole(String key) {
		try (Jedis jedis = pool.getResource()) {
			return jedis.incr(key);
		} catch (Exception e) {
			logger.error("incblackhole failed. key={}", key, e);
			return null;
		}
	}
	
	public Long incrString(String key) {
		try (Jedis jedis = pool.getResource()) {
			return jedis.incr(key);
		} catch (Exception e) {
			logger.error("incrString failed. key={}", key, e);
			return null;
		}
	}

	public boolean setString(String key, String obj, int time) {
		try (Jedis jedis = pool.getResource()) {
			String result = jedis.setex(key, time, obj);
			return "OK".equalsIgnoreCase(result);
		} catch (Exception e) {
			logger.error("setString failed. key={}", key, e);
			return false;
		}
	}

	/**
	 * Set key to value only if key does not exist (atomic SETNX + EXPIRE).
	 * Returns the existing value if key already exists, or null if this call set the key.
	 * This replaces the pattern: getString() + setString() inside synchronized block.
	 *
	 * @param key   Redis key
	 * @param value value to set if key doesn't exist
	 * @param expireSeconds TTL in seconds
	 * @return null if this call set the key successfully; the existing value if key already existed
	 */
	public String setnxString(String key, String value, int expireSeconds) {
		try (Jedis jedis = pool.getResource()) {
			Long result = jedis.setnx(key, value);
			if (result != null && result == 1L) {
				// Key was set by us (didn't exist before), set expiry
				jedis.expire(key, expireSeconds);
				return null; // indicates "we set it"
			} else {
				// Key already existed, return the existing value
				return jedis.get(key);
			}
		} catch (Exception e) {
			logger.error("setnxString failed. key={}", key, e);
			return null;
		}
	}

	public void setExpired(String key, int time) {
		try (Jedis jedis = pool.getResource()) {
			jedis.expire(key, time);
		} catch (Exception e) {
			logger.error("setExpired failed. key={}", key, e);
		}
	}

	public boolean setPipeline(String key, String value, int expireTime,
			String hashKey) {
		try (Jedis jedis = pool.getResource()) {
			Pipeline p = jedis.pipelined();
			Response<String> resp = p.setex(key, expireTime, value);
			p.hset(hashKey, key, "");		
			p.sync();
			String result = null;
			if (resp != null) {
				result = resp.get();
			}
			if (!("OK".equalsIgnoreCase(result))) {
				// Rollback: delete the partial data
				Pipeline rollback = jedis.pipelined();
				rollback.del(key);
				rollback.hdel(hashKey, key);
				rollback.sync();
				return false;
			}
			return true;
		} catch (Exception e) {
			logger.error("setPipeline failed. key={}", key, e);
			// Best-effort cleanup with a new connection
			try (Jedis cleanupJedis = pool.getResource()) {
				Pipeline p = cleanupJedis.pipelined();
				p.del(key);
				p.hdel(hashKey, key);
				p.sync();
			} catch (Exception ex) {
				logger.error("setPipeline cleanup failed. key={}", key, ex);
			}
			return false;
		}
	}
	
	public boolean setDelayDR(String key, String value, int expireTime,
			double score, int ossid) {
		try (Jedis jedis = pool.getResource()) {
			Pipeline p = jedis.pipelined();
			Response<String> resp = p.setex(key, expireTime, value);
			p.zadd("delayDR_"+ossid, score, key);	
			p.sadd("delayDR", "delayDR_"+ossid);
			p.sync();
			String result = null;
			if (resp != null) {
				result = resp.get();
			}
			if (!("OK".equalsIgnoreCase(result))) {
				Pipeline rollback = jedis.pipelined();
				rollback.del(key);
				rollback.zrem("delayDR", key);
				rollback.sync();
				return false;
			}
			return true;
		} catch (Exception e) {
			logger.error("setDelayDR failed. key={}", key, e);
			try (Jedis cleanupJedis = pool.getResource()) {
				Pipeline p = cleanupJedis.pipelined();
				p.del(key);
				p.zrem("delayDR", key);
				p.sync();
			} catch (Exception ex) {
				logger.error("setDelayDR cleanup failed. key={}", key, ex);
			}
			return false;
		}
	}
	
	public boolean setHashMapWithPipline(String keyword, Map<String, Map<String, String>> infos) {
		try (Jedis jedis = pool.getResource()) {
			Pipeline p = jedis.pipelined();
			List<String> keys = new ArrayList<String>();
			for (Map.Entry<String, Map<String, String>> entries: infos.entrySet()) {
				String routingKey = entries.getKey();
				//logger.info("routing redis value: {}, {}", routingKey, entries.getValue());
				String sufferInfo = "";
				if (routingKey.contains("_")) {
					sufferInfo = routingKey.split("_")[0];
					routingKey = routingKey.split("_")[1];
					
				}
				String lastIndex = "";
				if(sufferInfo.contains("RoutingRelay") 
						&& !sufferInfo.endsWith("RoutingRelay")) {
					lastIndex = sufferInfo.split("RoutingRelay")[1];
				}
				String key = keyword+lastIndex+"_"+routingKey;
				p.hmset(key, entries.getValue());
				keys.add(key);
			}
			p.sadd("routingkey", keys.toArray(new String[keys.size()]));
			p.sync();
			return true;
		} catch (Exception e) {
			logger.error("setHashMapWithPipline failed. key={}", keyword, e);
			return false;
		}
	}

	public void delPipeline(String key, String hashKey) {
		try (Jedis jedis = pool.getResource()) {
			Pipeline p = jedis.pipelined();
			p.del(key);
			p.hdel(hashKey, key);
			p.sync();
		} catch (Exception e) {
			logger.error("delPipeline failed. key={}", key, e);
		}
	}

	/**
	 * set object
	 */
	public boolean setObject(String key, Object obj, long time) {
		try (Jedis jedis = pool.getResource()) {
			String result = jedis.set(key, SerializableHandler.objectToString(obj));
			jedis.expireAt(key, time);
			return "OK".equalsIgnoreCase(result);
		} catch (Exception e) {
			logger.error("setObject failed. key={}", key, e);
			return false;
		}
	}
	
	public boolean lpush(String key, String obj) {
		try (Jedis jedis = pool.getResource()) {
			long result = jedis.lpush(key, obj);
			return result > 0;
		} catch (Exception e) {
			logger.error("lpush failed. key={}", key, e);
			return false;
		}
	}
	
	public boolean lpushArr(String setKey, String key, String[] obj) {
		try (Jedis jedis = pool.getResource()) {
			Pipeline p = jedis.pipelined();
		    p.lpush(key, obj);
		    p.sadd(setKey, key);
		    p.sync();
		    return true;
		} catch (Exception e) {
			logger.error("lpushArr failed. key={}", key, e);
			return false;
		}
	}

	/**
	 * set object
	 */
	public boolean setHash(String key, Map<String, String> map) {
		try (Jedis jedis = pool.getResource()) {
			String result = jedis.hmset(key, map);
			return "OK".equalsIgnoreCase(result);
		} catch (Exception e) {
			logger.error("setHash failed. key={}", key, e);
			return false;
		}
	}
	
	public boolean setHashAndSet(String setKey, String key, Map<String, String> map) {
		try (Jedis jedis = pool.getResource()) {
			Pipeline p = jedis.pipelined(); 
			p.hmset(key, map);
			p.sadd(setKey, key);
		    p.sync();
		    return true;
		} catch (Exception e) {
			logger.error("setHashAndSet failed. key={}", key, e);
			return false;
		}
	}

	/**
	 * get object
	 */
	public Object getObject(String key) {
		try (Jedis jedis = pool.getResource()) {
			String value = jedis.get(key);
			return SerializableHandler.stringToObject(value);
		} catch (Exception e) {
			logger.error("getObject failed. key={}", key, e);
			return null;
		}
	}
	
	public Set<String> zrange(String key, double start, double end) {
		try (Jedis jedis = pool.getResource()) {
			return jedis.zrangeByScore(key, start, end);
		} catch (Exception e) {
			logger.error("zrange failed. key={}", key, e);
			return null;
		}
	}
	
	public Set<String> zrangeByIndex(String key, long start, long end) {
		try (Jedis jedis = pool.getResource()) {
			return jedis.zrange(key, start, end);
		} catch (Exception e) {
			logger.error("zrangeByIndex failed. key={}", key, e);
			return null;
		}
	}

	public String getString(String key) {
		try (Jedis jedis = pool.getResource()) {
			return jedis.get(key);
		} catch (Exception e) {
			logger.error("getString failed. key={}", key, e);
			return null;
		}
	}
	
	public String rpop(String key) {
		try (Jedis jedis = pool.getResource()) {
			return jedis.rpop(key);
		} catch (Exception e) {
			logger.error("rpop failed. key={}", key, e);
			return null;
		}
	}

	/**
	 * Batch-pop up to {@code count} elements from the right (tail) of the list
	 * in a single Redis round-trip using LRANGE + LTRIM pipeline.
	 *
	 * Replaces N individual rpop() calls (N RTTs) with 2 pipelined commands
	 * (1 RTT), giving up to N× throughput improvement.
	 *
	 * Note: LRANGE + LTRIM is not atomic. This is safe when there is only one
	 * consumer thread per key (which is the case for priority sender queues).
	 *
	 * @param key   Redis list key
	 * @param count Maximum number of elements to pop
	 * @return List of popped values (oldest-first, right-to-left order), or empty list
	 */
	public List<String> rpopBatch(String key, int count) {
		if (count <= 0) {
			return java.util.Collections.emptyList();
		}
		try (Jedis jedis = pool.getResource()) {
			// LRANGE key -count -1  →  fetch last `count` elements (right side)
			// LTRIM  key 0 -(count+1) →  remove those elements atomically via pipeline
			Pipeline p = jedis.pipelined();
			Response<List<String>> rangeResp = p.lrange(key, -count, -1);
			p.ltrim(key, 0, -(count + 1));
			p.sync();
			List<String> result = rangeResp.get();
			if (result == null) {
				return java.util.Collections.emptyList();
			}
			// LRANGE returns left-to-right; reverse so index 0 = rightmost (oldest rpop order)
			java.util.Collections.reverse(result);
			return result;
		} catch (Exception e) {
			logger.error("rpopBatch failed. key={}", key, e);
			return java.util.Collections.emptyList();
		}
	}

	
	public String brpop(String key) {
		try (Jedis jedis = pool.getResource()) {
			List<String> list = jedis.brpop(500, key);
			if (list != null && list.size() == 2) {
				return list.get(1);
			}
			return null;
		} catch (Exception e) {
			logger.error("brpop failed. key={}", key, e);
			return null;
		}
	}

	public boolean setHash(String key, String field, String value) {
		try (Jedis jedis = pool.getResource()) {
			if (value == null) {
				jedis.hset(key, field, "");
			} else {
				jedis.hset(key, field, value);
			}
			return true;
		} catch (Exception e) {
			logger.error("setHash failed. key={}", key, e);
			return false;
		}
	}
	
	public boolean sadd(String key, List<String> values) {
		try (Jedis jedis = pool.getResource()) {
			jedis.sadd(key, values.toArray(new String[values.size()]));
			return true;
		} catch (Exception e) {
			logger.error("sadd failed. key={}", key, e);
			return false;
		}
	}

	public Set<String> getHashValue(String key) {
		try (Jedis jedis = pool.getResource()) {
			return jedis.hkeys(key);
		} catch (Exception e) {
			logger.error("getHashValue failed. key={}", key, e);
			return null;
		}
	}

	public List<String> getHashMap(String key, String[] s) throws Exception {
		try (Jedis jedis = pool.getResource()) {
			return jedis.hmget(key, s);
		} catch (Exception e) {
			logger.error("getHashMap failed. key={}", key, e);
			throw new Exception(e.toString());
		}
	}

	public Map<String, String> getHashAll(String key) {
		try (Jedis jedis = pool.getResource()) {
			return jedis.hgetAll(key);
		} catch (Exception e) {
			logger.error("getHashAll failed. key={}", key, e);
			return null;
		}
	}
	
	public Set<String> smembers(String key) {
		try (Jedis jedis = pool.getResource()) {
			return jedis.smembers(key);
		} catch (Exception e) {
			logger.error("smembers failed. key={}", key, e);
			return null;
		}
	}

	/**
	 * del object
	 */
	public boolean delObject(String key) {
		try (Jedis jedis = pool.getResource()) {
			jedis.del(key);
			return true;
		} catch (Exception e) {
			logger.error("delObject failed. key={}", key, e);
			return false;
		}
	}
	
	public boolean zrem(String key, String[] value) {
		try (Jedis jedis = pool.getResource()) {
			jedis.zrem(key, value);
			return true;
		} catch (Exception e) {
			logger.error("zrem failed. key={}", key, e);
			return false;
		}
	}

	public boolean hashINC(String key, String field) {
		try (Jedis jedis = pool.getResource()) {
			jedis.hincrBy(key, field, 1);
			return true;
		} catch (Exception e) {
			logger.error("hashINC failed. key={}", key, e);
			return false;
		}
	}
	
	public boolean delHash(String key, String field) {
		try (Jedis jedis = pool.getResource()) {
			jedis.hdel(key, field);
			return true;
		} catch (Exception e) {
			logger.error("delHash failed. key={}", key, e);
			return false;
		}
	}
	
    
    public boolean addStock(String key, long timemark, long num) {
		try (Jedis jedis = pool.getResource()) {
			String timemarkRedisKey = key+":timemark";
			jedis.set(timemarkRedisKey, String.valueOf(timemark));
	        jedis.incrBy(key, num);
	        return true;
		} catch (Exception e) {
			logger.error("addStock failed. key={}", key, e);
			return false;
		}
    }
    
    /**
     * 减库存
     * @param key
     * @param num
     * @return
     */
    public Long stock(String key, int num) {    	
		try (Jedis jedis = pool.getResource()) {
    		// 脚本里的KEYS参数
            List<String> keys = new ArrayList<>();
            keys.add(key);
            // 脚本里的ARGV参数
            List<String> args = new ArrayList<>();
            args.add(Integer.toString(num));
            return (Long)jedis.eval(STOCK_LUA, keys, args);
		} catch (Exception e) {
			logger.error("stock failed. key={}", key, e);
			return null;
		}
    }
    
	public void setPool(JedisPool pool) {
		this.pool = pool;
	}

	public JedisPool getPool() {
		return pool;
	}

	public static void main(String[] args) {

		// Jedis redis = new Jedis("192.168.23.112",6379);
		/*
		 * RedisConnection redis =new RedisConnection(false); JedisPool pool =
		 * RedisConnection.getSlavePool(); // Jedis redis1 = pool.getResource();
		 * //System.out.println(jedis); try { String key = "key1";
		 * redis.setHash(key, "hao"); redis.setHash(key, "hao1");
		 * 
		 * //redis.setObject(key, new Date(),12222);
		 * //System.out.println(redis.setObject(key, new Date(),1222));
		 * 
		 * 
		 * 
		 * // Map<String,String>map = redis.hgetAll(key); Set<String> set =
		 * redis.getHashValue(key); for (Iterator<String> i = set.iterator();
		 * i.hasNext();) { System.out.println(i.next()); }
		 * 
		 * } catch (Exception e) { e.printStackTrace(); } Object s
		 * =redis.getObject("3022710132355"); String role = redis1.info();
		 * //String s = role.substring(role.indexOf("role:")+5,
		 * role.indexOf("role:")+6); System.out.println(s);
		 * System.out.println(role);
		 */
		Jedis redis = new Jedis("192.168.23.191", 6378);
		/*
		 * RedisConnection redis =new RedisConnection((true)); JedisPool pool =
		 * RedisConnection.getSlavePool(); Jedis redis1 = pool.getResource();//
		 */// System.out.println(jedis);
			// GmmsUtility gmmsUtility =GmmsUtility.getInstance();
		String s = null;
		/*
		 * Pipeline p = redis.pipelined(); p.get("1002125424");
		 * p.del("1002125424"); List<Object> list = p.syncAndReturnAll();
		 */
		String[] s1 = new String[] { "s", "t" };
		List list = redis.hmget("ss", s1);
		for (int i = 0; i < list.size(); i++) {
			System.out.println(list.size());
		}
		/*
		 * GmmsMessage message =
		 * SerializableHandler.convertRedisMssage2GmmsMessage(s); String time =
		 * gmmsUtility.getRedisDateIn(message);
		 */
		System.out.println("s=" + s);
		// redis.hdel(time, "1002125424");
		// redis.del("1002125424");
		// redis.delHash(gmmsUtility.getRedisDateIn(message), "1002125424");
	}

}