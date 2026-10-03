package org.json;
import java.util.*;
import ai.hypermemetic.voicevault.JsonTestBridge;
public class JSONArray {
 public final List<Object> data;
 public JSONArray(){data=new ArrayList<>();}
 @SuppressWarnings("unchecked") public JSONArray(String text){data=(List<Object>)JsonTestBridge.read(text);}
 @SuppressWarnings("unchecked") public JSONArray(Object value){data=(List<Object>)value;}
 public JSONArray put(Object value){data.add(value instanceof JSONObject ? ((JSONObject)value).data : value);return this;}
 public int length(){return data.size();}
 public JSONObject getJSONObject(int index){return new JSONObject(data.get(index));}
 public String toString(){return JsonTestBridge.write(data);}
}
