/**
 * 
 */
package nta.catalog;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import nta.catalog.proto.CatalogProtos.KeyValueProto;
import nta.catalog.proto.CatalogProtos.KeyValueSetProto;
import nta.catalog.proto.CatalogProtos.KeyValueSetProtoOrBuilder;
import nta.common.ProtoObject;
import nta.engine.json.GsonCreator;

import com.google.common.collect.Maps;
import com.google.gson.annotations.Expose;

/**
 * @author Hyunsik Choi
 *
 */
public class Options implements ProtoObject<KeyValueSetProto>, Cloneable {
	@Expose(serialize=false,deserialize=false)
	private KeyValueSetProto proto = KeyValueSetProto.getDefaultInstance();
	@Expose(serialize=false,deserialize=false)
	private KeyValueSetProto.Builder builder = null;
	@Expose(serialize=false,deserialize=false)
	private boolean viaProto = false;
	
	@Expose
	private Map<String,String> keyVals;
	
	public Options() {
		builder = KeyValueSetProto.newBuilder();
	}
	
	public Options(KeyValueSetProto proto) {
		this.proto = proto;
		viaProto = true;
	}
	
	public Options(Options options) {
	  this();
	  options.initFromProto();
	  this.keyVals = Maps.newHashMap(options.keyVals);
	}
	
	public static Options create() {
	  return new Options();
	}
	
	public static Options create(Options options) {
    return new Options(options);
  }
	
	public void put(String key, String val) {
		initOptions();
		setModified();
		this.keyVals.put(key, val);
	}
	
	public void putAll(Options options) {
	  initOptions();
	  setModified();
	  this.keyVals.putAll(options.keyVals);
	}
	
	public String get(String key) {
		initOptions();
		return this.keyVals.get(key);
	}
	
	public String get(String key, String defaultVal) {
	  initOptions();
	  if(keyVals.containsKey(key))
	    return keyVals.get(key);
	  else {
	    return defaultVal;
	  }
	}
	
	public Iterator<Entry<String,String>> getAllKeyValus() {
	  initOptions();
	  return keyVals.entrySet().iterator();
	}
	
	public String delete(String key) {
		initOptions();
		return keyVals.remove(key);
	}
	
	@Override
	public boolean equals(Object object) {
		if(object instanceof Options) {
			Options other = (Options)object;
			initOptions();
			other.initOptions();
			for(Entry<String, String> entry : other.keyVals.entrySet()) {
				if(!keyVals.get(entry.getKey()).equals(entry.getValue()))
					return false;
			}
			return true;
		}
		
		return false;
	}
	
	@Override
  public Object clone() throws CloneNotSupportedException {    
    Options options = (Options) super.clone();
    initFromProto();
    options.proto = null;
    options.viaProto = false;
    options.builder = KeyValueSetProto.newBuilder();
    options.keyVals = keyVals != null ? new HashMap<String, String>(keyVals) :
      null;    
    return options;
	}
	
	@Override
	public KeyValueSetProto getProto() {
	  if(!viaProto) {
      mergeLocalToBuilder();
      proto = builder.build();
      viaProto = true;
    }	  
		return proto;
	}
	
	private void initOptions() {
		if (this.keyVals != null) {
			return;
		}
		KeyValueSetProtoOrBuilder p = viaProto ? proto : builder;
		this.keyVals = Maps.newHashMap();
		for(KeyValueProto keyval:p.getKeyvalList()) {
			this.keyVals.put(keyval.getKey(), keyval.getValue());
		}		
	}
	
	private void setModified() {
		if (viaProto || builder == null) {
			builder = KeyValueSetProto.newBuilder(proto);
		}
		viaProto = false;
	}
	
	private void mergeLocalToBuilder() {
		KeyValueProto.Builder kvBuilder = null;
		if(this.keyVals != null) {
			for(Entry<String,String> kv : keyVals.entrySet()) {
				kvBuilder = KeyValueProto.newBuilder();
				kvBuilder.setKey(kv.getKey());
				kvBuilder.setValue(kv.getValue());
				builder.addKeyval(kvBuilder.build());
			}
		}
	}

  @Override
  public void initFromProto() {
    initOptions();
  }
  
  public String toJSON() {
    initFromProto();
    return GsonCreator.getInstance().toJson(this, Options.class);
  }
}
