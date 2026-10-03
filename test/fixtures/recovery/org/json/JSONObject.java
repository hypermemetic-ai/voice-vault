package org.json;
import java.util.*;
import ai.hypermemetic.voicevault.JsonTestBridge;
public class JSONObject {
 public final Map<String,Object> data;
 public JSONObject(){data=new LinkedHashMap<>();}
 @SuppressWarnings("unchecked") public JSONObject(String text){data=(Map<String,Object>)JsonTestBridge.read(text);}
 @SuppressWarnings("unchecked") public JSONObject(Object data){this.data=(Map<String,Object>)data;}
 public JSONObject put(String key,Object value){data.put(key,value instanceof JSONArray ? ((JSONArray)value).data : value);return this;}
 public String optString(String key,String fallback){Object value=data.get(key);return value instanceof String ? (String)value : fallback;}
 public long optLong(String key,long fallback){Object value=data.get(key);return value instanceof Number ? ((Number)value).longValue() : fallback;}
 public boolean optBoolean(String key,boolean fallback){Object value=data.get(key);return value instanceof Boolean ? (Boolean)value : fallback;}
 public JSONArray optJSONArray(String key){Object value=data.get(key);return value instanceof List ? new JSONArray(value) : null;}
 public String toString(){return JsonTestBridge.write(data);}
}
