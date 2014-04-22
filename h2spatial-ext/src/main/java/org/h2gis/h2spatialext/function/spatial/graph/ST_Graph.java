/**
 * h2spatial is a library that brings spatial support to the H2 Java database.
 *
 * h2spatial is distributed under GPL 3 license. It is produced by the "Atelier SIG"
 * team of the IRSTV Institute <http://www.irstv.fr/> CNRS FR 2488.
 *
 * Copyright (C) 2007-2014 IRSTV (FR CNRS 2488)
 *
 * h2patial is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * h2spatial is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * h2spatial. If not, see <http://www.gnu.org/licenses/>.
 *
 * For more information, please consult: <http://www.orbisgis.org/>
 * or contact directly: info_at_orbisgis.org
 */

package org.h2gis.h2spatialext.function.spatial.graph;

import org.h2gis.h2spatialapi.AbstractFunction;
import org.h2gis.h2spatialapi.ScalarFunction;
import org.h2gis.utilities.GeometryTypeCodes;
import org.h2gis.utilities.JDBCUtilities;
import org.h2gis.utilities.SFSUtilities;
import org.h2gis.utilities.TableLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.List;

/**
 * ST_Graph produces two tables (nodes and edges) from an input table
 * containing LINESTRINGs or MULTILINESTRINGs in the given column and using the
 * given tolerance, and potentially orienting edges by slope. If the input
 * table has name 'input', then the output tables are named 'input_nodes' and
 * 'input_edges'. The nodes table consists of an integer node_id and a POINT
 * geometry representing each node. The edges table is a copy of the input
 * table with three extra columns: edge_id, start_node, and end_node. The
 * start_node and end_node correspond to the node_ids in the nodes table.
 * <p/>
 * If the specified geometry column of the input table contains geometries
 * other than LINESTRINGs or MULTILINESTRINGs, the operation will fail.
 * <p/>
 * A tolerance value may be given to specify the side length of a square
 * Envelope around each node used to snap together other nodes within the same
 * Envelope. Note, however, that edge geometries are left untouched.  Note also
 * that coordinates within a given tolerance of each other are not necessarily
 * snapped together. Only the first and last coordinates of a geometry are
 * considered to be potential nodes, and only nodes within a given tolerance of
 * each other are snapped together. The tolerance works only in metric units.
 * <p/>
 * A boolean value may be set to true to specify that edges should be oriented
 * by the z-value of their first and last coordinates (decreasing).
 *
 * @author Adam Gouge
 * @author Erwan Bocher
 */
public class ST_Graph extends AbstractFunction implements ScalarFunction {

    private static Connection connection;

    private TableLocation tableName;
    private TableLocation nodesName;
    private TableLocation edgesName;

    private double tolerance;
    private boolean orientBySlope;
    private Integer spatialFieldIndex;

    private final Logger logger = LoggerFactory.getLogger("gui." + ST_Graph.class);

    public ST_Graph() {
        this(null, null, 0.0, false);
    }

    /**
     * Constructor
     *
     * @param connection    Connection
     * @param inputTable    Input table name
     * @param tolerance     Tolerance
     * @param orientBySlope True if edges should be oriented by the z-value of
     *                      their first and last coordinates (decreasing)
     */
    public ST_Graph(Connection connection,
                    String inputTable,
                    double tolerance,
                    boolean orientBySlope) {
        if (tolerance < 0) {
            throw new IllegalArgumentException("Only positive tolerances are allowed.");
        }
        if (connection != null) {
            this.connection = SFSUtilities.wrapConnection(connection);
        }
        if (inputTable != null) {
            this.tableName = TableLocation.parse(inputTable);
            this.nodesName = new TableLocation(tableName.getCatalog(), tableName.getSchema(),
                    tableName.getTable() + "_NODES");
            this.edgesName = new TableLocation(tableName.getCatalog(), tableName.getSchema(),
                    tableName.getTable() + "_EDGES");
        }
        this.tolerance = tolerance;
        this.orientBySlope = orientBySlope;
        addProperty(PROP_REMARKS, "ST_Graph produces two tables (nodes and edges) from an input table " +
                "containing LINESTRINGs or MULTILINESTRINGs in the given column and using the " +
                "given tolerance, and potentially orienting edges by slope. If the input " +
                "table has name 'input', then the output tables are named 'input_nodes' and " +
                "'input_edges'. The nodes table consists of an integer node_id and a POINT " +
                "geometry representing each node. The edges table is a copy of the input " +
                "table with three extra columns: edge_id, start_node, and end_node. The " +
                "start_node and end_node correspond to the node_ids in the nodes table.\n" +

                "If the specified geometry column of the input table contains geometries " +
                "other than LINESTRINGs or MULTILINESTRINGs, the operation will fail.\n" +

                "A tolerance value may be given to specify the side length of a square " +
                "Envelope around each node used to snap together other nodes within the same " +
                "Envelope. Note, however, that edge geometries are left untouched.  Note also " +
                "that coordinates within a given tolerance of each other are not necessarily " +
                "snapped together. Only the first and last coordinates of a geometry are " +
                "considered to be potential nodes, and only nodes within a given tolerance of " +
                "each other are snapped together. The tolerance works only in metric units.\n" +

                "A boolean value may be set to true to specify that edges should be oriented " +
                "by the z-value of their first and last coordinates (decreasing). "
        );
    }

    @Override
    public String getJavaStaticMethod() {
        return "createGraph";
    }

    /**
     * Create the nodes and edges tables from the input table containing
     * LINESTRINGs or MULTILINESTRINGs.
     * <p/>
     * Since no column is specified in this signature, we take the first
     * geometry column we find.
     * <p/>
     * If the input table has name 'input', then the output tables are named
     * 'input_nodes' and 'input_edges'.
     *
     * @param connection Connection
     * @param tableName  Input table containing LINESTRINGs or MULTILINESTRINGs
     * @return true if both output tables were created
     * @throws SQLException
     */
    public static boolean createGraph(Connection connection,
                                      String tableName) throws SQLException {
        return createGraph(connection, tableName, null);
    }

    /**
     * Create the nodes and edges tables from the input table containing
     * LINESTRINGs or MULTILINESTRINGs in the given column.
     * <p/>
     * If the input table has name 'input', then the output tables are named
     * 'input_nodes' and 'input_edges'.
     *
     * @param connection       Connection
     * @param tableName        Input table
     * @param spatialFieldName Name of column containing LINESTRINGs or
     *                         MULTILINESTRINGs
     * @return true if both output tables were created
     * @throws SQLException
     */
    public static boolean createGraph(Connection connection,
                                      String tableName,
                                      String spatialFieldName) throws SQLException {
        // The default tolerance is zero.
        return createGraph(connection, tableName, spatialFieldName, 0.0);
    }

    /**
     * Create the nodes and edges tables from the input table containing
     * LINESTRINGs or MULTILINESTRINGs in the given column and using the given
     * tolerance.
     * <p/>
     * The tolerance value is used specify the side length of a square Envelope
     * around each node used to snap together other nodes within the same
     * Envelope. Note, however, that edge geometries are left untouched.
     * Note also that coordinates within a given tolerance of each
     * other are not necessarily snapped together. Only the first and last
     * coordinates of a geometry are considered to be potential nodes, and
     * only nodes within a given tolerance of each other are snapped
     * together. The tolerance works only in metric units.
     * <p/>
     * If the input table has name 'input', then the output tables are named
     * 'input_nodes' and 'input_edges'.
     *
     * @param connection       Connection
     * @param tableName        Input table
     * @param spatialFieldName Name of column containing LINESTRINGs or
     *                         MULTILINESTRINGs
     * @param tolerance        Tolerance
     * @return true if both output tables were created
     * @throws SQLException
     */
    public static boolean createGraph(Connection connection,
                                      String tableName,
                                      String spatialFieldName,
                                      double tolerance) throws SQLException {
        // By default we do not orient by slope.
        return createGraph(connection, tableName, spatialFieldName, tolerance, false);
    }

    /**
     * Create the nodes and edges tables from the input table containing
     * LINESTRINGs or MULTILINESTRINGs in the given column and using the given
     * tolerance, and potentially orienting edges by slope.
     * <p/>
     * The tolerance value is used specify the side length of a square Envelope
     * around each node used to snap together other nodes within the same
     * Envelope. Note, however, that edge geometries are left untouched.
     * Note also that coordinates within a given tolerance of each
     * other are not necessarily snapped together. Only the first and last
     * coordinates of a geometry are considered to be potential nodes, and
     * only nodes within a given tolerance of each other are snapped
     * together. The tolerance works only in metric units.
     * <p/>
     * The boolean orientBySlope is set to true if edges should be oriented by
     * the z-value of their first and last coordinates (decreasing).
     * <p/>
     * If the input table has name 'input', then the output tables are named
     * 'input_nodes' and 'input_edges'.
     *
     * @param connection       Connection
     * @param tableName        Input table
     * @param spatialFieldName Name of column containing LINESTRINGs or
     *                         MULTILINESTRINGs
     * @param tolerance        Tolerance
     * @param orientBySlope    True if edges should be oriented by the z-value of
     *                         their first and last coordinates (decreasing)
     * @return true if both output tables were created
     * @throws SQLException
     */
    public static boolean createGraph(Connection connection,
                                      String tableName,
                                      String spatialFieldName,
                                      double tolerance,
                                      boolean orientBySlope) throws SQLException {
        ST_Graph f = new ST_Graph(connection, tableName, tolerance, orientBySlope);
        // Check for a primary key
        final DatabaseMetaData md = connection.getMetaData();
        final int pkIndex = JDBCUtilities.getIntegerPrimaryKey(md, f.tableName.getTable());
        if (pkIndex == 0) {
            throw new IllegalStateException("Table " + f.tableName.getTable()
                    + " must contain a single integer primary key.");
        }
        final String pkColName = JDBCUtilities.getFieldName(md, f.tableName.getTable(), pkIndex);
        // Check the geometry column type;
        f.getSpatialFieldIndexAndColumnCount(spatialFieldName);
        f.checkGeometryType();
        final String geomCol = JDBCUtilities.getFieldName(md, f.tableName.getTable(), f.spatialFieldIndex);
        final Statement st = connection.createStatement();
        try {
            f.firstFirstLastLast(st, pkColName, geomCol);
            f.makeEnvelopes(st);
            f.nodesTable(st);
            f.edgesTable(st);
            f.checkForNullEdgeEndpoints(st);
            if (f.orientBySlope) {
                f.orientBySlope(st);
            }
        } finally {
            st.close();
        }
        return true;
    }

    private void checkGeometryType() throws SQLException {
        final String fieldName = JDBCUtilities.getFieldName(connection.getMetaData(), tableName.getTable(), spatialFieldIndex);
        final int type = SFSUtilities.getGeometryType(connection, tableName, fieldName);
        if (type != GeometryTypeCodes.LINESTRING && type != GeometryTypeCodes.MULTILINESTRING) {
            throw new IllegalArgumentException("Column " + fieldName + " must be of type LINESTRING " +
                    "or MULTILINESTRING.");
        }
    }

    /**
     * Get the column index of the given spatial field, or the first one found
     * if none is given (specified by null).
     *
     * @param spatialFieldName Spatial field name
     * @throws SQLException
     */
    private void getSpatialFieldIndexAndColumnCount(String spatialFieldName) throws SQLException {
        // Find the name of the first geometry column if not provided by the user.
        if (spatialFieldName == null) {
            List<String> geomFields = SFSUtilities.getGeometryFields(connection, tableName);
            if (!geomFields.isEmpty()) {
                spatialFieldName = geomFields.get(0);
            } else {
                throw new SQLException("Table " + tableName + " does not contain a geometry field.");
            }
        }
        // Set up tables
        final ResultSet columns = connection.getMetaData()
                .getColumns(tableName.getCatalog(), tableName.getSchema(), tableName.getTable(), null);
        try {
            while (columns.next()) {
                if (columns.getString("COLUMN_NAME").equalsIgnoreCase(spatialFieldName)) {
                    spatialFieldIndex = columns.getRow();
                }
            }
        } finally {
            columns.close();
        }
        if (spatialFieldIndex == null) {
            throw new SQLException("Geometry field " + spatialFieldName + " of table " + tableName + " not found");
        }
    }

    private String expand(String geom, double tol) {
        return "ST_Expand(" + geom + ", " + tol + ", " + tol + ")";
    }

    private void firstFirstLastLast(Statement st, String pkCol, String geomCol) throws SQLException {
        logger.info("Selecting the first coordinate of the first geometry and " +
                "the last coordinate of the last geometry...");
        final String numGeoms = "ST_NumGeometries(" + geomCol + ")";
        final String firstGeom = "ST_GeometryN(" + geomCol + ", 1)";
        final String firstPointFirstGeom = "ST_PointN(" + firstGeom + ", 1)";
        final String lastGeom = "ST_GeometryN(" + geomCol + ", " + numGeoms + ")";
        final String lastPointLastGeom = "ST_PointN(" + lastGeom + ", ST_NumPoints(" + lastGeom + "))";
        st.execute("drop TABLE if exists COORDS");
        if (tolerance > 0) {
            st.execute("CREATE CACHED LOCAL TEMPORARY TABLE COORDS AS "
                    + "SELECT " + pkCol + " EDGE_ID, "
                    + firstPointFirstGeom + " START_POINT, "
                    + expand(firstPointFirstGeom, tolerance) + " START_POINT_EXP, "
                    + lastPointLastGeom + " END_POINT, "
                    + expand(lastPointLastGeom, tolerance) + " END_POINT_EXP "
                    + "FROM " + tableName);
        } else {
            // If the tolerance is zero, there is no need to call ST_Expand.
            st.execute("CREATE CACHED LOCAL TEMPORARY TABLE COORDS AS "
                    + "SELECT " + pkCol + " EDGE_ID, "
                    + firstPointFirstGeom + " START_POINT, "
                    + lastPointLastGeom + " END_POINT "
                    + "FROM " + tableName);
        }
    }

    /**
     * Make a big table of all points in the coords table with an envelope around each point.
     * We will use this table to remove duplicate points.
     */
    private void makeEnvelopes(Statement st) throws SQLException {
        st.execute("DROP TABLE IF EXISTS PTS;");
        if (tolerance > 0) {
            logger.info("Calculating envelopes around coordinates...");
            // Putting all points and their envelopes together...
            st.execute("CREATE CACHED LOCAL TEMPORARY TABLE PTS( " +
                    "ID INT AUTO_INCREMENT PRIMARY KEY, " +
                    "THE_GEOM POINT, " +
                    "AREA POLYGON " +
                    ") AS " +
                    "SELECT NULL, START_POINT, START_POINT_EXP FROM COORDS " +
                    "UNION ALL " +
                    "SELECT NULL, END_POINT, END_POINT_EXP FROM COORDS;");
            // Putting a spatial index on the envelopes...
            st.execute("CREATE SPATIAL INDEX ON PTS(AREA);");
        } else {
            logger.info("Preparing temporary nodes table from coordinates...");
            // If the tolerance is zero, we just put all points together
            st.execute("CREATE CACHED LOCAL TEMPORARY TABLE PTS( " +
                    "ID INT AUTO_INCREMENT PRIMARY KEY, " +
                    "THE_GEOM POINT" +
                    ") AS " +
                    "SELECT NULL, START_POINT FROM COORDS " +
                    "UNION ALL " +
                    "SELECT NULL, END_POINT FROM COORDS;");
            // Putting a spatial index on the points themselves...
            st.execute("CREATE SPATIAL INDEX ON PTS(THE_GEOM);");
        }
    }

    /**
     * Create the nodes table.
     */
    private void nodesTable(Statement st) throws SQLException {
        logger.info("Creating the nodes table...");
        // Creating nodes table by removing copies from the pts table.
        st.execute("DROP TABLE IF EXISTS " + nodesName + ";");
        if (tolerance > 0) {
            st.execute("CREATE TABLE " + nodesName + "(" +
                    "NODE_ID INT AUTO_INCREMENT PRIMARY KEY, " +
                    "THE_GEOM POINT, " +
                    "EXP POLYGON" +
                    ") AS " +
                    "SELECT NULL, A.THE_GEOM, A.AREA FROM PTS A, PTS B " +
                    "WHERE A.AREA && B.AREA " +
                    "GROUP BY A.ID " +
                    "HAVING A.ID=MIN(B.ID);");
        } else {
            // If the tolerance is zero, we can create the NODES table
            // by using = rather than &&.
            st.execute("CREATE TABLE " + nodesName + "(" +
                    "NODE_ID INT AUTO_INCREMENT PRIMARY KEY, " +
                    "THE_GEOM POINT " +
                    ") AS " +
                    "SELECT NULL, A.THE_GEOM FROM PTS A, PTS B " +
                    "WHERE A.THE_GEOM && B.THE_GEOM AND A.THE_GEOM=B.THE_GEOM " +
                    "GROUP BY A.ID " +
                    "HAVING A.ID=MIN(B.ID);");
        }
    }

    /**
     * Create the edges table.
     */
    private void edgesTable(Statement st) throws SQLException {
        logger.info("Creating the edges table...");
        st.execute("DROP TABLE IF EXISTS " + edgesName + ";");
        if (tolerance > 0) {
            st.execute("CREATE SPATIAL INDEX ON " + nodesName + "(EXP);");
            st.execute("CREATE SPATIAL INDEX ON COORDS(START_POINT_EXP);");
            st.execute("CREATE SPATIAL INDEX ON COORDS(END_POINT_EXP);");
            st.execute("CREATE TABLE " + edgesName + " AS " +
                    "SELECT EDGE_ID, " +
                    "(SELECT NODE_ID FROM " + nodesName +
                    " WHERE " + nodesName + ".EXP && COORDS.START_POINT_EXP LIMIT 1) START_NODE, " +
                    "(SELECT NODE_ID FROM " + nodesName +
                    " WHERE " + nodesName + ".EXP && COORDS.END_POINT_EXP LIMIT 1) END_NODE " +
                    "FROM COORDS;");
            st.execute("ALTER TABLE " + nodesName + " DROP COLUMN EXP;");
        } else {
            st.execute("CREATE SPATIAL INDEX ON " + nodesName + "(THE_GEOM);");
            st.execute("CREATE SPATIAL INDEX ON COORDS(START_POINT);");
            st.execute("CREATE SPATIAL INDEX ON COORDS(END_POINT);");
            // If the tolerance is zero, then we can use = on the geometries
            // instead of && on the envelopes.
            st.execute("CREATE TABLE " + edgesName + " AS " +
                    "SELECT EDGE_ID, " +
                    "(SELECT NODE_ID FROM " + nodesName +
                    " WHERE " + nodesName + ".THE_GEOM && COORDS.START_POINT " +
                    "AND " + nodesName + ".THE_GEOM=COORDS.START_POINT LIMIT 1) START_NODE, " +
                    "(SELECT NODE_ID FROM " + nodesName +
                    " WHERE " + nodesName + ".THE_GEOM && COORDS.END_POINT " +
                    "AND " + nodesName + ".THE_GEOM=COORDS.END_POINT LIMIT 1) END_NODE " +
                    "FROM COORDS;");
        }
    }

    private void orientBySlope(Statement st) throws SQLException {
        logger.info("Orienting edges by slope...");
        st.execute("UPDATE " + edgesName + " c " +
                    "SET START_NODE=END_NODE, " +
                    "    END_NODE=START_NODE " +
                    "WHERE (SELECT ST_Z(A.THE_GEOM) < ST_Z(B.THE_GEOM) " +
                            "FROM " + nodesName + " A, " + nodesName + " B " +
                            "WHERE C.START_NODE=A.NODE_ID AND C.END_NODE=B.NODE_ID);");
    }

    private void checkForNullEdgeEndpoints(Statement st) throws SQLException {
        logger.info("Checking for null edge endpoints...");
        final ResultSet nullEdges = st.executeQuery("SELECT COUNT(*) FROM " + edgesName + " WHERE " +
                "START_NODE IS NULL OR END_NODE IS NULL;");
        try {
            nullEdges.next();
            final int n = nullEdges.getInt(1);
            if (n > 0) {
                String msg = "There " + (n == 1 ? "is one edge " : "are " + n + " edges ");
                throw new IllegalStateException(msg + "with a null start node or end node. " +
                        "Try using a slightly smaller tolerance.");
            }
        } finally {
            nullEdges.close();
        }
    }
}