/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.end2end;

import static org.apache.phoenix.util.TestUtil.TEST_PROPERTIES;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyList;
import static org.mockito.Matchers.anyListOf;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyMap;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isNull;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;

import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.exception.SQLExceptionCode;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.jdbc.PhoenixEmbeddedDriver;
import org.apache.phoenix.query.ConnectionQueryServices;
import org.apache.phoenix.schema.PColumn;
import org.apache.phoenix.schema.PName;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.PTableKey;
import org.apache.phoenix.schema.PTableType;
import org.apache.phoenix.schema.TableAlreadyExistsException;
import org.apache.phoenix.util.PropertiesUtil;
import org.junit.Test;
import org.mockito.Mockito;

public class AppendOnlySchemaIT extends BaseHBaseManagedTimeIT {
    
    private void createTableWithSameSchema(boolean notExists, boolean sameClient) throws Exception {
        // use a spyed ConnectionQueryServices so we can verify calls to getTable
        ConnectionQueryServices connectionQueryServices =
                Mockito.spy(driver.getConnectionQueryServices(getUrl(),
                    PropertiesUtil.deepCopy(TEST_PROPERTIES)));
        Properties props = new Properties();
        props.putAll(PhoenixEmbeddedDriver.DEFFAULT_PROPS.asMap());

        try (Connection conn1 = connectionQueryServices.connect(getUrl(), props);
                Connection conn2 = sameClient ? conn1 : connectionQueryServices.connect(getUrl(), props)) {
            // create sequence for auto partition
            conn1.createStatement().execute("CREATE SEQUENCE metric_id_seq CACHE 1");
            // create base table
            conn1.createStatement().execute("CREATE TABLE metric_table (metricId INTEGER NOT NULL, metricVal DOUBLE, CONSTRAINT PK PRIMARY KEY(metricId))" 
                    + " APPEND_ONLY_SCHEMA = true, UPDATE_CACHE_FREQUENCY=1, AUTO_PARTITION_SEQ=metric_id_seq");
            // create view
            String ddl =
                    "CREATE VIEW " + (notExists ? "IF NOT EXISTS" : "")
                            + " view1( hostName varchar NOT NULL,"
                            + " CONSTRAINT HOSTNAME_PK PRIMARY KEY (hostName))"
                            + " AS SELECT * FROM metric_table"
                            + " APPEND_ONLY_SCHEMA = true, UPDATE_CACHE_FREQUENCY=300000";
            conn1.createStatement().execute(ddl);
            conn1.createStatement().execute("UPSERT INTO view1(hostName, metricVal) VALUES('host1', 1.0)");
            conn1.commit();
            reset(connectionQueryServices);

            // execute same ddl
            try {
                conn2.createStatement().execute(ddl);
                if (!notExists) {
                    fail("Create Table should fail");
                }
            }
            catch (TableAlreadyExistsException e) {
                if (notExists) {
                    fail("Create Table should not fail");
                }
            }
            
            // verify getTable rpcs
            verify(connectionQueryServices, sameClient ? never() : atMost(1)).getTable((PName)isNull(), eq(new byte[0]), eq(Bytes.toBytes("VIEW1")), anyLong(), anyLong());
            
            // verify create table rpcs
            verify(connectionQueryServices, never()).createTable(anyListOf(Mutation.class),
                any(byte[].class), any(PTableType.class), anyMap(), anyList(), any(byte[][].class),
                eq(false));

            // upsert one row
            conn2.createStatement().execute("UPSERT INTO view1(hostName, metricVal) VALUES('host2', 2.0)");
            conn2.commit();
            // verify data in base table
            ResultSet rs = conn2.createStatement().executeQuery("SELECT * from metric_table");
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            assertEquals(1.0, rs.getDouble(2), 1e-6);
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            assertEquals(2.0, rs.getDouble(2), 1e-6);
            assertFalse(rs.next());
            // verify data in view
            rs = conn2.createStatement().executeQuery("SELECT * from view1");
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            assertEquals(1.0, rs.getDouble(2), 1e-6);
            assertEquals("host1", rs.getString(3));
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            assertEquals(2.0, rs.getDouble(2), 1e-6);
            assertEquals("host2", rs.getString(3));
            assertFalse(rs.next());
        }
    }

    @Test
    public void testSameSchemaWithNotExistsSameClient() throws Exception {
        createTableWithSameSchema(true, true);
    }
    
    @Test
    public void testSameSchemaWithNotExistsDifferentClient() throws Exception {
        createTableWithSameSchema(true, false);
    }
    
    @Test
    public void testSameSchemaSameClient() throws Exception {
        createTableWithSameSchema(false, true);
    }
    
    @Test
    public void testSameSchemaDifferentClient() throws Exception {
        createTableWithSameSchema(false, false);
    }

    private void createTableAddColumns(boolean sameClient) throws Exception {
        Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
        try (Connection conn1 = DriverManager.getConnection(getUrl(), props);
                Connection conn2 = sameClient ? conn1 : DriverManager.getConnection(getUrl(), props)) {
            // create sequence for auto partition
            conn1.createStatement().execute("CREATE SEQUENCE metric_id_seq CACHE 1");
            // create base table
            conn1.createStatement().execute("CREATE TABLE metric_table (metricId INTEGER NOT NULL, metricVal DOUBLE, CONSTRAINT PK PRIMARY KEY(metricId))" 
                    + " APPEND_ONLY_SCHEMA = true, UPDATE_CACHE_FREQUENCY=1, AUTO_PARTITION_SEQ=metric_id_seq");
            // create view
            String ddl =
                    "CREATE VIEW IF NOT EXISTS"
                            + " view1( hostName varchar NOT NULL,"
                            + " CONSTRAINT HOSTNAME_PK PRIMARY KEY (hostName))"
                            + " AS SELECT * FROM metric_table"
                            + " APPEND_ONLY_SCHEMA = true, UPDATE_CACHE_FREQUENCY=300000";
            conn1.createStatement().execute(ddl);
            
            conn1.createStatement().execute("UPSERT INTO view1(hostName, metricVal) VALUES('host1', 1.0)");
            conn1.commit();

            // execute ddl adding a pk column and regular column
            ddl =
                    "CREATE VIEW IF NOT EXISTS"
                            + " view1( hostName varchar NOT NULL, instanceName varchar, metricVal2 double"
                            + " CONSTRAINT HOSTNAME_PK PRIMARY KEY (hostName, instancename))"
                            + " AS SELECT * FROM metric_table"
                            + " APPEND_ONLY_SCHEMA = true, UPDATE_CACHE_FREQUENCY=300000";
            conn2.createStatement().execute(ddl);

            conn2.createStatement().execute(
                "UPSERT INTO view1(hostName, instanceName, metricVal, metricval2) VALUES('host2', 'instance2', 21.0, 22.0)");
            conn2.commit();
            
            conn1.createStatement().execute("UPSERT INTO view1(hostName, metricVal) VALUES('host3', 3.0)");
            conn1.commit();
            
            // verify data exists
            ResultSet rs = conn2.createStatement().executeQuery("SELECT * from view1");
            
            // verify the two columns were added correctly
            PTable table =
                    conn2.unwrap(PhoenixConnection.class).getTable(new PTableKey(null, "VIEW1"));
            List<PColumn> pkColumns = table.getPKColumns();
            assertEquals(3,table.getPKColumns().size());
            assertEquals("METRICID", pkColumns.get(0).getName().getString());
            assertEquals("HOSTNAME", pkColumns.get(1).getName().getString());
            assertEquals("INSTANCENAME", pkColumns.get(2).getName().getString());
            List<PColumn> columns = table.getColumns();
            assertEquals("METRICID", columns.get(0).getName().getString());
            assertEquals("METRICVAL", columns.get(1).getName().getString());
            assertEquals("HOSTNAME", columns.get(2).getName().getString());
            assertEquals("INSTANCENAME", columns.get(3).getName().getString());
            assertEquals("METRICVAL2", columns.get(4).getName().getString());
            
            // verify the data
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            assertEquals(1.0, rs.getDouble(2), 1e-6);
            assertEquals("host1", rs.getString(3));
            assertEquals(null, rs.getString(4));
            assertEquals(0.0, rs.getDouble(5), 1e-6);
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            assertEquals(21.0, rs.getDouble(2), 1e-6);
            assertEquals("host2", rs.getString(3));
            assertEquals("instance2", rs.getString(4));
            assertEquals(22.0, rs.getDouble(5), 1e-6);
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            assertEquals(3.0, rs.getDouble(2), 1e-6);
            assertEquals("host3", rs.getString(3));
            assertEquals(null, rs.getString(4));
            assertEquals(0.0, rs.getDouble(5), 1e-6);
            assertFalse(rs.next());
        }
    }
    
    @Test
    public void testCreateTableAddColumnsSameClient() throws Exception {
        createTableAddColumns(true);
    }
    
    @Test
    public void testCreateTableAddColumnsDifferentClient() throws Exception {
        createTableAddColumns(false);
    }

    public void testCreateTableDropColumns() throws Exception {
        Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
        try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
            String ddl =
                    "create table IF NOT EXISTS TEST( id1 char(2) NOT NULL," + " col1 integer,"
                            + " col2 integer," + " CONSTRAINT NAME_PK PRIMARY KEY (id1))"
                            + " APPEND_ONLY_SCHEMA = true, UPDATE_CACHE_FREQUENCY=300000";
            conn.createStatement().execute(ddl);
            conn.createStatement().execute("UPSERT INTO TEST VALUES('a', 11)");
            conn.commit();

            // execute ddl while dropping a column
            ddl = "alter table TEST drop column col1";
            try {
                conn.createStatement().execute(ddl);
                fail("Dropping a column from a table with APPEND_ONLY_SCHEMA=true should fail");
            } catch (SQLException e) {
                assertEquals(SQLExceptionCode.CANNOT_DROP_COL_APPEND_ONLY_SCHEMA.getErrorCode(),
                    e.getErrorCode());
            }
        }
    }

    @Test
    public void testValidateAttributes() throws Exception {
        Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
        try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
            try {
                conn.createStatement().execute(
                    "create table IF NOT EXISTS TEST1 ( id char(1) NOT NULL,"
                            + " col1 integer NOT NULL,"
                            + " CONSTRAINT NAME_PK PRIMARY KEY (id, col1))"
                            + " APPEND_ONLY_SCHEMA = true");
                fail("UPDATE_CACHE_FREQUENCY attribute must not be set to ALWAYS if APPEND_ONLY_SCHEMA is true");
            } catch (SQLException e) {
                assertEquals(SQLExceptionCode.UPDATE_CACHE_FREQUENCY_INVALID.getErrorCode(),
                    e.getErrorCode());
            }
            
            conn.createStatement().execute(
                "create table IF NOT EXISTS TEST1 ( id char(1) NOT NULL,"
                        + " col1 integer NOT NULL"
                        + " CONSTRAINT NAME_PK PRIMARY KEY (id, col1))"
                        + " APPEND_ONLY_SCHEMA = true, UPDATE_CACHE_FREQUENCY=1000");
            try {
                conn.createStatement().execute(
                    "create view IF NOT EXISTS MY_VIEW (val1 integer NOT NULL) AS SELECT * FROM TEST1"
                            + " UPDATE_CACHE_FREQUENCY=1000");
                fail("APPEND_ONLY_SCHEMA must be true for a view if it is true for the base table ");
            }
            catch (SQLException e) {
                assertEquals(SQLExceptionCode.VIEW_APPEND_ONLY_SCHEMA.getErrorCode(),
                    e.getErrorCode());
            }
        }
    }
    
    @Test
    public void testUpsertRowToDeletedTable() throws Exception {
        Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
        try (Connection conn1 = DriverManager.getConnection(getUrl(), props);
                Connection conn2 = DriverManager.getConnection(getUrl(), props)) {
            // create sequence for auto partition
            conn1.createStatement().execute("CREATE SEQUENCE metric_id_seq CACHE 1");
            // create base table
            conn1.createStatement().execute("CREATE TABLE metric_table (metricId INTEGER NOT NULL, metricVal DOUBLE, CONSTRAINT PK PRIMARY KEY(metricId))" 
                    + " APPEND_ONLY_SCHEMA = true, UPDATE_CACHE_FREQUENCY=1, AUTO_PARTITION_SEQ=metric_id_seq");
            // create view
            String ddl =
                    "CREATE VIEW IF NOT EXISTS"
                            + " view1( hostName varchar NOT NULL,"
                            + " CONSTRAINT HOSTNAME_PK PRIMARY KEY (hostName))"
                            + " AS SELECT * FROM metric_table"
                            + " APPEND_ONLY_SCHEMA = true, UPDATE_CACHE_FREQUENCY=300000";
            conn1.createStatement().execute(ddl);
            
            // drop the table using a different connection
            conn2.createStatement().execute("DROP VIEW view1");
            
            // upsert one row
            conn1.createStatement().execute("UPSERT INTO view1(hostName, metricVal) VALUES('host1', 1.0)");
            // upsert doesn't fail since base table still exists
            conn1.commit();
        }
    }

}