package com.inmobi.grill.driver.jdbc;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.hsqldb.persist.ScriptRunner;
import org.hsqldb.util.*;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.parse.SemanticException;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import com.inmobi.grill.api.GrillException;
import com.inmobi.grill.api.query.QueryHandle;
import com.inmobi.grill.api.query.QueryStatus;
import com.inmobi.grill.api.query.QueryStatus.Status;
import com.inmobi.grill.api.query.ResultColumn;
import com.inmobi.grill.api.query.ResultColumnType;
import com.inmobi.grill.api.query.ResultRow;
import com.inmobi.grill.server.api.driver.GrillResultSet;
import com.inmobi.grill.server.api.driver.GrillResultSetMetadata;
import com.inmobi.grill.server.api.driver.InMemoryResultSet;
import com.inmobi.grill.server.api.driver.QueryCompletionListener;
import com.inmobi.grill.server.api.query.PreparedQueryContext;
import com.inmobi.grill.server.api.query.QueryContext;

public class TestJDBCFinal {
  Configuration baseConf;
  JDBCDriver driver;

  @BeforeTest

  public void testCreateJdbcDriver() throws Exception {
    baseConf = new Configuration();
    baseConf.set(JDBCDriverConfConstants.JDBC_DRIVER_CLASS, "org.h2.Driver");
    baseConf.set(JDBCDriverConfConstants.JDBC_DB_URI,
        "jdbc:h2:mem:jdbcTestDB;MODE=MYSQL");
    baseConf.set(JDBCDriverConfConstants.JDBC_USER, "sa");
    baseConf.set(JDBCDriverConfConstants.JDBC_PASSWORD, "");
    baseConf.set(JDBCDriverConfConstants.JDBC_QUERY_REWRITER_CLASS,
        ColumnarSQLRewriter.class.getName());

    driver = new JDBCDriver();
    driver.configure(baseConf);
    assertNotNull(driver);
    assertTrue(driver.configured);
    System.out.println("Driver configured!");
    SessionState.start(new HiveConf(ColumnarSQLRewriter.class));

  }

  @AfterTest
  public void close() throws Exception {
    driver.close();
    System.out.println("Driver closed!");
  }

  // create table and insert data
  synchronized void createTables() throws Exception {
    Connection conn = null;
    Statement stmt = null;

    String createFact = "create table  sales_fact (time_key integer, item_key integer, branch_key integer, "
        + "location_key integer, dollars_sold double, units_sold integer)";

    String createDim1 = "create table  time_dim ( time_key integer, day date, day_of_week integer, "
        + "month integer, quarter integer, year integer )";

    String createDim2 = "create table item_dim ( item_key integer, item_name varchar(500))";

    String createDim3 = "create table branch_dim ( branch_key integer, branch_name varchar(100))";

    String createDim4 = "create table location_dim (location_key integer,location_name varchar(100))";

    String insertFact = "insert into sales_fact values "
        + "(1001,234,119,223,3000.58,56), (1002,235,120,224,3456.26,62), (1003,236,121,225,6745.23,97),(1004,237,122,226,8753.49,106)";

    String insertDim1 = "insert into time_dim values "
        + "(1001,'1900-01-01',1,1,1,1900),(1002,'1900-01-02',2,1,1,1900),(1003,'1900-01-03',3,1,1,1900),(1004,'1900-01-04',4,1,1,1900)";

    String insertDim2 = "insert into item_dim values "
        + "(234,'item1'),(235,'item2'),(236,'item3'),(237,'item4')";

    String insertDim3 = "insert into branch_dim values "
        + "(119,'branch1'),(120,'branch2'),(121,'branch3'),(122,'branch4') ";

    String insertDim4 = "insert into location_dim values "
        + "(223,'loc1'),(224,'loc2'),(225,'loc4'),(226,'loc4')";
    try {
      conn = driver.getConnection(baseConf);
      stmt = conn.createStatement();
      // stmt.execute(dropTables);
      stmt.execute(createFact);
      stmt.execute(createDim1);
      stmt.execute(createDim2);
      stmt.execute(createDim3);
      stmt.execute(createDim4);
      stmt.execute(insertFact);
      stmt.execute(insertDim1);
      stmt.execute(insertDim2);
      stmt.execute(insertDim3);
      stmt.execute(insertDim4);

    } finally {
      if (stmt != null) {
        stmt.close();
      }
      if (conn != null) {
        conn.close();
      }
    }
  }

  @Test
  public void createSchema() throws Exception {
    createTables();
  }

  @Test
  public void testExecute1() throws Exception {
    testCreateJdbcDriver();
    String query =

    "select fact.time_key,time_dim.day_of_week,time_dim.day,"
        + "sum(fact.dollars_sold) dollars_sold " 
        + "from sales_fact fact "
        + "inner join time_dim time_dim on fact.time_key = time_dim.time_key "
        + "where time_dim.day between '1900-01-01' and '1900-01-03' "
        + "group by fact.time_key,time_dim.day_of_week,time_dim.day "
        + "order by dollars_sold desc";

    QueryContext context = new QueryContext(query, "SA", baseConf);
    GrillResultSet resultSet = driver.execute(context);
    assertNotNull(resultSet);

    if (resultSet instanceof InMemoryResultSet) {
      InMemoryResultSet rs = (InMemoryResultSet) resultSet;
      GrillResultSetMetadata rsMeta = rs.getMetadata();
      assertEquals(rsMeta.getColumns().size(), 4);

      ResultColumn col1 = rsMeta.getColumns().get(0);
      assertEquals(col1.getType(), ResultColumnType.INT);
      assertEquals(col1.getName(), "time_key".toUpperCase());

      ResultColumn col2 = rsMeta.getColumns().get(1);
      assertEquals(col2.getType(), ResultColumnType.INT);
      assertEquals(col2.getName(), "day_of_week".toUpperCase());

      ResultColumn col3 = rsMeta.getColumns().get(2);
      assertEquals(col3.getType(), ResultColumnType.DATE);
      assertEquals(col3.getName(), "day".toUpperCase());

      ResultColumn col4 = rsMeta.getColumns().get(3);
      assertEquals(col4.getType(), ResultColumnType.DOUBLE);
      assertEquals(col4.getName(), "dollars_sold".toUpperCase());

      while (rs.hasNext()) {
        ResultRow row = rs.next();
        List<Object> rowObjects = row.getValues();
        System.out.println(rowObjects);
      }

      if (rs instanceof JDBCResultSet) {
        ((JDBCResultSet) rs).close();
      }
    }
  }

  @Test
  public void testExecute2() throws Exception {
    testCreateJdbcDriver();
    String query =

   "select fact.time_key,time_dim.day_of_week,time_dim.day, "
        + "sum(fact.dollars_sold) dollars_sold "
        + "from sales_fact fact "
        + "inner join time_dim time_dim on fact.time_key = time_dim.time_key "
        + "inner join item_dim on fact.item_key = item_dim.item_key and item_name = 'item2' "
        + "inner join branch_dim on fact.branch_key = branch_dim.branch_key and branch_name = 'branch2' "
        + "inner join location_dim on fact.location_key = location_dim.location_key "
        + "where time_dim.day between '1900-01-01' and '1900-01-04' "
        + "and location_dim.location_name = 'loc2' "
        + "group by fact.time_key,time_dim.day_of_week,time_dim.day "
        + "order by dollars_sold  desc "; 

    QueryContext context = new QueryContext(query, "SA", baseConf);
    GrillResultSet resultSet = driver.execute(context);
    assertNotNull(resultSet);

    if (resultSet instanceof InMemoryResultSet) {
      InMemoryResultSet rs = (InMemoryResultSet) resultSet;
      GrillResultSetMetadata rsMeta = rs.getMetadata();
      assertEquals(rsMeta.getColumns().size(), 4);

      ResultColumn col1 = rsMeta.getColumns().get(0);
      assertEquals(col1.getType(), ResultColumnType.INT);
      assertEquals(col1.getName(), "time_key".toUpperCase());

      ResultColumn col2 = rsMeta.getColumns().get(1);
      assertEquals(col2.getType(), ResultColumnType.INT);
      assertEquals(col2.getName(), "day_of_week".toUpperCase());

      ResultColumn col3 = rsMeta.getColumns().get(2);
      assertEquals(col3.getType(), ResultColumnType.DATE);
      assertEquals(col3.getName(), "day".toUpperCase());

      ResultColumn col4 = rsMeta.getColumns().get(3);
      assertEquals(col4.getType(), ResultColumnType.DOUBLE);
      assertEquals(col4.getName(), "dollars_sold".toUpperCase());

      while (rs.hasNext()) {
        ResultRow row = rs.next();
        List<Object> rowObjects = row.getValues();
        System.out.println(rowObjects);
      }

      if (rs instanceof JDBCResultSet) {
        ((JDBCResultSet) rs).close();
      }
    }
  }

}