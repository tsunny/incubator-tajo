/**
 * 
 */
package nta.catalog;

import java.util.Iterator;
import java.util.Map.Entry;

import nta.annotation.Optional;
import nta.annotation.Required;
import nta.catalog.proto.CatalogProtos.StoreType;
import nta.catalog.proto.CatalogProtos.TableProto;
import nta.catalog.proto.CatalogProtos.TableProtoOrBuilder;
import nta.engine.json.GsonCreator;

import com.google.common.base.Objects;
import com.google.gson.Gson;
import com.google.gson.annotations.Expose;

/**
 * @author Hyunsik Choi
 */
public class TableMetaImpl implements TableMeta {
	protected TableProto proto = TableProto.getDefaultInstance();
	protected TableProto.Builder builder = null;
	protected boolean viaProto = false;	
	
	@Expose @Required 
	protected Schema schema;
	@Expose @Required 
	protected StoreType storeType;
	@Expose @Optional
	protected Options options;
	
	private TableMetaImpl() {
	  builder = TableProto.newBuilder();
	}
	
	public TableMetaImpl(Schema schema, StoreType type, Options options) {
	  this();
	  this.schema = schema;
    this.storeType = type;
    this.options = new Options(options);
  }
	
	public TableMetaImpl(TableProto proto) {
		this.proto = proto;
		this.viaProto = true;
	}
	
	public void setStorageType(StoreType storeType) {
    setModified();
    this.storeType = storeType;
  }	
	
	public StoreType getStoreType() {
		TableProtoOrBuilder p = viaProto ? proto : builder;
		
		if(storeType != null) {
			return this.storeType;
		}
		if(!p.hasStoreType()) {
			return null;
		}
		this.storeType = p.getStoreType();
		
		return this.storeType;		
	}
	
  public void setSchema(Schema schema) {
    setModified();
    this.schema = schema;
  }
	
	public Schema getSchema() {
		TableProtoOrBuilder p = viaProto ? proto : builder;
		
		if(schema != null) {
			return this.schema;
		}
		if(!proto.hasSchema()) {
		  return null;
		}
		this.schema = new Schema(p.getSchema());
		
		return this.schema;
	}
	
  public void setOptions(Options options) {
    setModified();
    this.options = options;
  }

  private Options initOptions() {
    TableProtoOrBuilder p = viaProto ? proto : builder;
    if(this.options != null) {
      return this.options;
    }
    if(!p.hasParams()) {
      return null;
    }
    this.options = new Options(p.getParams());
    
    return this.options;
  }  

  @Override
  public void putOption(String key, String val) {
    setModified();
    initOptions().put(key, val);
  }
  

  @Override
  public String getOption(String key) {    
    return initOptions().get(key);
  }

  @Override
  public String getOption(String key, String defaultValue) {
    return initOptions().get(key, defaultValue);
  }
  
  @Override
  public Iterator<Entry<String,String>> getOptions() {    
    return initOptions().getAllKeyValus();
  }
	
	public boolean equals(Object object) {
		if(object instanceof TableMetaImpl) {
			TableMetaImpl other = (TableMetaImpl) object;
			
			return this.getProto().equals(other.getProto());
		}
		
		return false;		
	}
	
	public int hashCode() {
	  return Objects.hashCode(getSchema(), storeType);
	}
	
	@Override
	public Object clone() throws CloneNotSupportedException {    
	  initFromProto();
	  TableMetaImpl meta = (TableMetaImpl) super.clone();	  
	  meta.proto = null;
    meta.viaProto = false;
    meta.builder = TableProto.newBuilder();
    meta.schema = (Schema) schema.clone();
    meta.storeType = storeType;
    meta.options = (Options) (options != null ? options.clone() : null);
    
    return meta;
	}
	
	public String toString() {
    StringBuilder sb = new StringBuilder();
    if(viaProto) {
      return proto.toString();
    }
    
    sb.append("{");
    if(schema != null) {
      sb.append("schema {")
      .append(schema.toString())
      .append("}");
    }
    
    if(storeType != null) {
      sb.append("storeType: {")
      .append(storeType)
      .append("}");
    }
    
    if(options != null) {
      sb.append("options: {")
      .append(options)
      .append("}");
    }
    
    return sb.toString();
  }
	
	////////////////////////////////////////////////////////////////////////
	// ProtoObject
	////////////////////////////////////////////////////////////////////////
	@Override
	public TableProto getProto() {
	  if(!viaProto) {
      mergeLocalToBuilder();
      proto = builder.build();
      viaProto = true;  
    }
		return proto;
	}

  private void setModified() {
    if (viaProto || builder == null) {
      builder = TableProto.newBuilder(proto);
    }
    this.viaProto = false;
  }
	
	private void mergeLocalToBuilder() {
    if (this.builder == null) {      
      this.builder = TableProto.newBuilder(proto);
    }
	  
	  if (this.schema != null) {
	    builder.setSchema(this.schema.getProto());
	  }

	  if (this.storeType != null) {
      builder.setStoreType(storeType);
    }

		if (this.options != null) {
		  builder.setParams(options.getProto());
		}
	}
	
  ////////////////////////////////////////////////////////////////////////
  // For Json
  ////////////////////////////////////////////////////////////////////////	
	private void mergeProtoToLocal() {
		TableProtoOrBuilder p = viaProto ? proto : builder;
		if (schema == null) {
			schema = new Schema(p.getSchema());
		}
		if (storeType == null && p.hasStoreType()) {
			storeType = p.getStoreType();
		}
		if (options == null && p.hasParams()) {
			options = new Options(p.getParams());
		}
	}
	
	public void initFromProto() {
		mergeProtoToLocal();
    schema.initFromProto();
	}
	
	public String toJSON() {
		initFromProto();
		Gson gson = GsonCreator.getInstance();
		return gson.toJson(this, TableMeta.class);
	}
}
