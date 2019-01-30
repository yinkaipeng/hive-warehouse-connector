package com.hortonworks.spark.sql.hive.llap;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import com.google.common.base.Joiner;
import com.hortonworks.spark.sql.hive.llap.util.HiveQlUtil;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.conf.MetastoreConf;
import org.apache.hadoop.hive.ql.metadata.Table;
import org.apache.hadoop.hive.serde.serdeConstants;
import org.apache.hive.streaming.HiveStreamingConnection;
import org.apache.hive.streaming.StreamingConnection;
import org.apache.hive.streaming.StreamingException;
import org.apache.hive.streaming.StrictDelimitedInputWriter;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.sources.v2.writer.DataWriter;
import org.apache.spark.sql.sources.v2.writer.WriterCommitMessage;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HiveStreamingDataWriter implements DataWriter<InternalRow> {
  public static final Joiner RECORD_JOINER = Joiner.on(",").useForNull("");
  private static Logger LOG = LoggerFactory.getLogger(HiveStreamingDataWriter.class);

  private String jobId;
  private StructType schema;
  private int partitionId;
  private long taskId;
  private long epochId;
  private String db;
  private String table;
  private List<String> partition;
  private String metastoreUri;
  private StreamingConnection streamingConnection;
  private long commitAfterNRows;
  private long rowsWritten = 0;
  private String metastoreKrbPrincipal;
  private String escapeDelimiter;
  private String quoteDelimiter;

  public HiveStreamingDataWriter(String jobId, StructType schema, long commitAfterNRows, int partitionId, long
      taskId, long epochId, String db, String table, List<String> partition, final String metastoreUri,
    final String metastoreKrbPrincipal) {
    this.jobId = jobId;
    this.schema = schema;
    this.partitionId = partitionId;
    this.taskId = taskId;
    this.epochId = epochId;
    this.db = db;
    this.table = table;
    this.partition = partition;
    this.metastoreUri = metastoreUri;
    this.commitAfterNRows = commitAfterNRows;
    this.metastoreKrbPrincipal = metastoreKrbPrincipal;
    try {
      createStreamingConnection();
    } catch (StreamingException e) {
      throw new RuntimeException("Unable to create hive streaming connection", e);
    }
  }

  private void createStreamingConnection() throws StreamingException {
    final StrictDelimitedInputWriter strictDelimitedInputWriter = StrictDelimitedInputWriter.newBuilder()
      .withFieldDelimiter(',').build();
    HiveConf hiveConf = new HiveConf();
    hiveConf.set(MetastoreConf.ConfVars.THRIFT_URIS.getHiveName(), metastoreUri);
    // isolated classloader and shadeprefix are required for reflective instantiation of outputformat class when
    // creating hive streaming connection (isolated classloader to load different hive versions and shadeprefix for
    // HIVE-19494)
    hiveConf.setVar(HiveConf.ConfVars.HIVE_CLASSLOADER_SHADE_PREFIX, "shadehive");
    hiveConf.set(MetastoreConf.ConfVars.CATALOG_DEFAULT.getHiveName(), "hive");

    // HiveStreamingCredentialProvider requires metastore uri and kerberos principal to obtain delegation token to
    // talk to secure metastore. When HiveConf is created above, hive-site.xml is used from classpath. This option
    // is just an override in case if kerberos principal cannot be obtained from hive-site.xml.
    if (metastoreKrbPrincipal != null) {
      hiveConf.set(MetastoreConf.ConfVars.KERBEROS_PRINCIPAL.getHiveName(), metastoreKrbPrincipal);
    }

    LOG.info("Creating hive streaming connection..");
    streamingConnection = HiveStreamingConnection.newBuilder()
      .withDatabase(db)
      .withTable(table)
      .withStaticPartitionValues(partition)
      .withRecordWriter(strictDelimitedInputWriter)
      .withHiveConf(hiveConf)
      .withAgentInfo(jobId + "(" + partitionId + ")")
      .connect();
    Table tableHandle = streamingConnection.getTable();
    this.escapeDelimiter = tableHandle.getMetadata().getProperty(serdeConstants.ESCAPE_CHAR);
    this.quoteDelimiter = tableHandle.getMetadata().getProperty(serdeConstants.QUOTE_CHAR);
    streamingConnection.beginTransaction();
    LOG.info("{} created hive streaming connection.", streamingConnection.getAgentInfo());
  }

  @Override
  public void write(final InternalRow record) throws IOException {
    try {
      streamingConnection.write(formatRecordForQl(record).getBytes(Charset.forName("UTF-8")));
      rowsWritten++;
      if (rowsWritten > 0 && commitAfterNRows > 0 && (rowsWritten % commitAfterNRows == 0)) {
        LOG.info("Committing transaction after rows: {}", rowsWritten);
        streamingConnection.commitTransaction();
        streamingConnection.beginTransaction();
      }
    } catch (StreamingException e) {
      throw new IOException(e);
    }
  }

  private String formatRecordForQl(InternalRow record) {
    return RECORD_JOINER.join(HiveQlUtil.formatRecord(schema, record, escapeDelimiter, quoteDelimiter));
  }

  @Override
  public WriterCommitMessage commit() throws IOException {
    try {
      streamingConnection.commitTransaction();
    } catch (StreamingException e) {
      throw new IOException(e);
    }
    String msg = "Committed jobId: " + jobId + " partitionId: " + partitionId + " taskId: " + taskId +
        " epochId: " + epochId + " connectionStats: " + streamingConnection.getConnectionStats();
    streamingConnection.close();
    LOG.info("Closing streaming connection on commit. Msg: {} rowsWritten: {}", rowsWritten);
    return new SimpleWriterCommitMessage(msg);
  }

  @Override
  public void abort() throws IOException {
    if (streamingConnection != null) {
      try {
        streamingConnection.abortTransaction();
      } catch (StreamingException e) {
        throw new IOException(e);
      }
      String msg = "Aborted jobId: " + jobId + " partitionId: " + partitionId + " taskId: " + taskId +
          " epochId: " + epochId + " connectionStats: " + streamingConnection.getConnectionStats();
      streamingConnection.close();
      LOG.info("Closing streaming connection on abort. Msg: {} rowsWritten: {}", msg, rowsWritten);
    }
  }
}

