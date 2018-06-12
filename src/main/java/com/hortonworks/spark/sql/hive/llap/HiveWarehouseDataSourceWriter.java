package com.hortonworks.spark.sql.hive.llap;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.spark.sql.SaveMode;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.sources.v2.writer.DataWriterFactory;
import org.apache.spark.sql.sources.v2.writer.SupportsWriteInternalRow;
import org.apache.spark.sql.sources.v2.writer.WriterCommitMessage;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.util.SerializableConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Option;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.sql.Connection;
import java.util.Map;

import static com.hortonworks.spark.sql.hive.llap.util.HiveQlUtil.loadInto;
import static java.lang.String.format;

public class HiveWarehouseDataSourceWriter implements SupportsWriteInternalRow {
  protected String jobId;
  protected StructType schema;
  protected Path path;
  protected Configuration conf;
  protected Map<String, String> options;
  private static Logger LOG = LoggerFactory.getLogger(HiveWarehouseDataSourceWriter.class);

  public HiveWarehouseDataSourceWriter(Map<String, String> options, String jobId, StructType schema,
      Path path, Configuration conf) {
    this.options = options;
    this.jobId = jobId;
    this.schema = schema;
    this.path = new Path(path, jobId);
    this.conf = conf;
  }

  @Override public DataWriterFactory<InternalRow> createInternalRowWriterFactory() {
    ByteArrayOutputStream confByteArrayStream = new ByteArrayOutputStream();
    byte[] confBytes;
    try(DataOutputStream confByteData = new DataOutputStream(confByteArrayStream)) {
      conf.write(confByteData);
      confBytes = confByteArrayStream.toByteArray();
    } catch(Exception e) {
      throw new RuntimeException(e);
    }
    return new HiveWarehouseDataWriterFactory(jobId, schema, path.toString(), confBytes);
  }

  @Override public void commit(WriterCommitMessage[] messages) {
    try {
      String url = HWConf.RESOLVED_HS2_URL.getFromOptionsMap(options);
      String user = HWConf.USER.getFromOptionsMap(options);
      String dbcp2Configs = HWConf.DBCP2_CONF.getFromOptionsMap(options);
      String database = HWConf.DEFAULT_DB.getFromOptionsMap(options);
      String table = options.get("table");
      try (Connection conn = DefaultJDBCWrapper.getConnector(Option.empty(), url, user, dbcp2Configs)) {
        DefaultJDBCWrapper.executeUpdate(conn, database, loadInto(this.path.toString(), database, table));
      } catch (java.sql.SQLException e) {
        throw new RuntimeException(e);
      }
    } finally {
      try {
        path.getFileSystem(conf).delete(path, true);
      } catch(Exception e) {
        LOG.warn("Failed to cleanup temp dir {}", path.toString());
      }
      LOG.info("Commit job {}", jobId);
    }
  }

  @Override public void abort(WriterCommitMessage[] messages) {
    try {
      path.getFileSystem(conf).delete(path, true);
    } catch(Exception e) {
      LOG.warn("Failed to cleanup temp dir {}", path.toString());
    }
    LOG.error("Aborted DataWriter job {}", jobId);
  }

}