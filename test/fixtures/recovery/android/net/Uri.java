package android.net;
import java.util.*;
public class Uri {
 final java.net.URI uri;public Uri(String value){uri=java.net.URI.create(value);}
 public List<String> getPathSegments(){String path=uri.getPath();return path==null || path.isEmpty() ? new ArrayList<>() : Arrays.asList(path.substring(1).split("/",-1));}
 public String getLastPathSegment(){List<String> parts=getPathSegments();return parts.isEmpty() ? null : parts.get(parts.size()-1);}
 public String toString(){return uri.toString();}
 public static class Builder {String scheme,authority,path="";public Builder scheme(String scheme){this.scheme=scheme;return this;}public Builder authority(String authority){this.authority=authority;return this;}public Builder appendPath(String path){this.path+="/"+path;return this;}public Uri build(){return new Uri(scheme+"://"+authority+path);}}
}
