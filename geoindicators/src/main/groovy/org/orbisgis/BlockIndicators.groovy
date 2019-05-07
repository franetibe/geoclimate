package org.orbisgis

import groovy.transform.BaseScript
import org.orbisgis.datamanager.JdbcDataSource
import org.orbisgis.datamanagerapi.dataset.ITable
import org.orbisgis.processmanagerapi.IProcess


@BaseScript Geoclimate geoclimate

/**
 * This process is used to compute basic statistical operations on a specific variable from a lower scale (for
 * example the sum of each building volume constituting a block to calculate the block volume)
 *
 * @param inputLowerScaleTableName the table name where are stored low scale objects and the id of the upper scale
 * zone they are belonging to (e.g. buildings)
 * @param inputUpperScaleTableName the table of the upper scale where the informations has to be aggregated to
 * @param inputIdUp the ID of the upper scale
 * @param inputVarAndOperations a map containing as key the informations that has to be transformed from the lower to
 * the upper scale and as values a LIST of operation to apply to this information (e.g. ["building_area":["SUM", "NB_DENS"]]).
 * Operations should be in the following list:
 *          --> "SUM": sum the geospatial variables at the upper scale
 *          --> "AVG": average the geospatial variables at the upper scale
 *          --> "GEOM_AVG": average the geospatial variables at the upper scale using a geometric average
 *          --> "DENS": sum the geospatial variables at the upper scale and divide by the area of the upper scale
 *          --> "NB_DENS" : count the number of lower scale objects within the upper scale object and divide by the upper
 *          scale area. NOTE THAT THE THREE FIRST LETTERS OF THE MAP KEY WILL BE USED TO CREATE THE NAME OF THE INDICATOR
 *          (e.g. "BUI" in the case of the example given above)
 * @param prefixName String use as prefix to name the output table
 *
 * @return A database table name.
 * @author Jérémy Bernard
 */
static IProcess unweightedOperationFromLowerScale() {
return processFactory.create(
        "Unweighted statistical operations from lower scale",
        [inputLowerScaleTableName: String,inputUpperScaleTableName: String, inputIdUp: String,
         inputVarAndOperations: String[], prefixName: String, datasource: JdbcDataSource],
        [outputTableName : String],
        { inputLowerScaleTableName, inputUpperScaleTableName, inputIdUp,
          inputVarAndOperations, prefixName, datasource ->

            def geometricFieldUp = "the_geom"

            // The name of the outputTableName is constructed
            String baseName = "unweighted_operation_from_lower_scale"
            String outputTableName = prefixName + "_" + baseName

            // List of possible operations
            def ops = ["SUM","AVG", "GEOM_AVG", "DENS", "NB_DENS"]

            String query = "CREATE INDEX IF NOT EXISTS id_l ON $inputLowerScaleTableName($inputIdUp); "+
                            "CREATE INDEX IF NOT EXISTS id_ucorr ON $inputUpperScaleTableName($inputIdUp); "+
                            "DROP TABLE IF EXISTS $outputTableName; CREATE TABLE $outputTableName AS SELECT "


            inputVarAndOperations.each {var, operations ->
                operations.each{op ->
                    op = op.toUpperCase()
                    if(ops.contains(op)){
                        if(op=="GEOM_AVG"){
                            query += "EXP(1.0/COUNT(*)*SUM(LOG(a.$var))) AS ${op+"_"+var},"
                        }
                        else if(op=="DENS"){
                            query += "SUM(a.$var::float)/ST_AREA(b.$geometricFieldUp) AS ${op+"_"+var},"
                        }
                        else if(op=="NB_DENS"){
                            query += "COUNT(a.*)/ST_AREA(b.$geometricFieldUp) AS ${var[0..2]+"_"+op},"
                        }
                        else{
                            query += "$op(a.$var::float) AS ${op+"_"+var},"
                        }
                    }
                }

            }
            query += "b.$inputIdUp FROM $inputLowerScaleTableName a, $inputUpperScaleTableName b " +
                    "WHERE a.$inputIdUp = b.$inputIdUp GROUP BY b.$inputIdUp"
            logger.info("Executing $query")
            datasource.execute query
            [outputTableName: outputTableName]
            }
    )}

/**
 * This process is used to compute weighted average and standard deviation on a specific variable from a lower scale (for
 * example the mean building roof height within a reference spatial unit where the roof height values are weighted
 * by the values of building area)
 *
 *  @param inputLowerScaleTableName the table name where are stored low scale objects and the id of the upper scale
 *  zone they are belonging to (e.g. buildings)
 *  @param inputUpperScaleTableName the table of the upper scale where the informations has to be aggregated to
 *  @param inputIdUp the ID of the upper scale
 *  @param inputVarWeightsOperations a map containing as key the informations that has to be transformed from the lower to
 *  the upper scale, and as value a map containing as key the variable that has to be used as weight and as values
 *  a LIST of operation to apply to this couple (information/weight). Note that the allowed operations should be in
 *  the following list:
 *           --> "STD": weighted standard deviation
 *           --> "AVG": weighted mean
 *  @param prefixName String use as prefix to name the output table
 *
 * @return A database table name.
 * @author Jérémy Bernard
 */
static IProcess weightedAggregatedStatistics() {
    return processFactory.create(
            "Weighted statistical operations from lower scale",
            [inputLowerScaleTableName: String,inputUpperScaleTableName: String, inputIdUp: String,
             inputVarWeightsOperations: String, prefixName: String, datasource: JdbcDataSource],
            [outputTableName : String],
            { inputLowerScaleTableName, inputUpperScaleTableName, inputIdUp,
              inputVarWeightsOperations, prefixName, datasource ->
                def ops = ["AVG", "STD"]

                // The name of the outputTableName is constructed
                String baseName = "weighted_aggregated_statistics"
                String outputTableName = prefixName + "_" + baseName

                // To avoid overwriting the output files of this step, a unique identifier is created
                def uid_out = System.currentTimeMillis()

                // Temporary table names
                def weighted_mean = "weighted_mean"+uid_out

                // The weighted mean is calculated in all cases since it is useful for the STD calculation
                def weightedMeanQuery = "CREATE INDEX IF NOT EXISTS id_l ON $inputLowerScaleTableName($inputIdUp); "+
                        "CREATE INDEX IF NOT EXISTS id_ucorr ON $inputUpperScaleTableName($inputIdUp); "+
                        "DROP TABLE IF EXISTS $weighted_mean; CREATE TABLE $weighted_mean($inputIdUp INTEGER,"
                def nameAndType = ""
                def weightedMean = ""
                inputVarWeightsOperations.each{var, weights ->
                    weights.each{weight, operations ->
                        nameAndType += "weighted_avg_${var}_$weight DOUBLE DEFAULT 0,"
                        weightedMean += "SUM(a.$var*a.$weight) / SUM(a.$weight) AS weighted_avg_${var}_$weight,"
                    }
                }
                weightedMeanQuery += nameAndType[0..-2] + ") AS (SELECT b.$inputIdUp, ${weightedMean[0..-2]}" +
                        " FROM $inputLowerScaleTableName a, $inputUpperScaleTableName b " +
                        "WHERE a.$inputIdUp = b.$inputIdUp GROUP BY b.$inputIdUp)"
                datasource.execute(weightedMeanQuery.toString())

                // The weighted std is calculated if needed and only the needed fields are returned
                def weightedStdQuery = "CREATE INDEX IF NOT EXISTS id_lcorr ON $weighted_mean($inputIdUp); " +
                        "DROP TABLE IF EXISTS $outputTableName; CREATE TABLE $outputTableName AS SELECT b.$inputIdUp,"
                inputVarWeightsOperations.each{var, weights ->
                    weights.each{weight, operations ->
                        // The operation names are transformed into upper case
                        operations.replaceAll({s -> s.toUpperCase()})
                        if(operations.contains("AVG")) {
                            weightedStdQuery += "b.weighted_avg_${var}_$weight,"
                        }
                        if(operations.contains("STD")) {
                            weightedStdQuery += "POWER(SUM(a.$weight*POWER(a.$var-b.weighted_avg_${var}_$weight,2))/" +
                                    "SUM(a.$weight),0.5) AS weighted_std_${var}_$weight,"
                        }
                    }
                }
                weightedStdQuery = weightedStdQuery[0..-2] +" FROM $inputLowerScaleTableName a, $weighted_mean b " +
                        "WHERE a.$inputIdUp = b.$inputIdUp GROUP BY b.$inputIdUp"

                logger.info("Executing $weightedStdQuery")
                datasource.execute weightedStdQuery

                // The temporary tables are deleted
                datasource.execute("DROP TABLE IF EXISTS $weighted_mean".toString())

                [outputTableName: outputTableName]
            }
    )}

/**
 * This process is used to compute the Perkins SKill Score (included within a [0 - 1] interval) of the distribution
 * of building direction within a block. This indicator gives an idea of the building direction variability within
 * each block. The length of the building SMBR sides is considered to calculate a distribution of building orientation.
 * Then the distribution is used to calculate the Perkins SKill Score. The distribution has an "angle_range_size"
 * interval that has to be defined by the user (default 15).
 *
 * @param datasource A connexion to a database (H2GIS, PostGIS, ...) where are stored the input Table and in which
 * the resulting database will be stored
 * @param buildingTableName the name of the input ITable where are stored the geometry field and the block ID
 * @param correlationTableName the name of the input ITable where are stored the relationships between blocks and buildings
 * @param angleRangeSize the range size (in °) of each interval angle used to calculate the distribution of building direction
 * (should be a divisor of 180 - default 15°)
 * @param prefixName String use as prefix to name the output table
 *
 * @return A database table name.
 * @author Jérémy Bernard
 */
static IProcess blockPerkinsSkillScoreBuildingDirection() {
    return processFactory.create(
            "Block Perkins skill score building direction",
            [buildingTableName: String,correlationTableName: String,
             angleRangeSize: int, prefixName: String, datasource: JdbcDataSource],
            [outputTableName : String],
            { buildingTableName, correlationTableName, angleRangeSize = 15, prefixName, datasource->

                def geometricField = "the_geom"
                def idFieldBu = "id_build"
                def idFieldBl = "id_block"

                // The name of the outputTableName is constructed
                String baseName = "block_perkins_skill_score_building_direction"
                String outputTableName = prefixName + "_" + baseName

                // Test whether the angleRangeSize is a divisor of 180°
                if (180%angleRangeSize==0 & 180/angleRangeSize>1){
                    // To avoid overwriting the output files of this step, a unique identifier is created
                    def uid_out = System.currentTimeMillis()

                    // Temporary table names
                    def build_min_rec = "build_min_rec"+uid_out
                    def build_dir360 = "build_dir360"+uid_out
                    def build_dir180 = "build_dir180"+uid_out
                    def build_dir_dist = "build_dir_dist"+uid_out
                    def build_dir_tot = "build_dir_tot"+uid_out


                    // The minimum diameter of the minimum rectangle is created for each building
                    datasource.execute(("CREATE INDEX IF NOT EXISTS id_bua ON $buildingTableName($idFieldBu); "+
                                        "CREATE INDEX IF NOT EXISTS id_bub ON $correlationTableName($idFieldBu); "+
                                        "DROP TABLE IF EXISTS $build_min_rec; CREATE TABLE $build_min_rec AS "+
                                        "SELECT b.$idFieldBu, b.$idFieldBl, ST_MINIMUMDIAMETER(ST_MINIMUMRECTANGLE(a.$geometricField)) "+
                                        "AS the_geom FROM $buildingTableName a, $correlationTableName b "+
                                        "WHERE a.$idFieldBu = b.$idFieldBu").toString())

                    // The length and direction of the smallest and the longest sides of the Minimum rectangle are calculated
                    datasource.execute(("CREATE INDEX IF NOT EXISTS id_bua ON $build_min_rec($idFieldBu);" +
                                        "DROP TABLE IF EXISTS $build_dir360; CREATE TABLE $build_dir360 AS "+
                                        "SELECT $idFieldBl, ST_LENGTH(a.the_geom) AS LEN_L, "+
                                        "ST_AREA(b.the_geom)/ST_LENGTH(a.the_geom) AS LEN_H, "+
                                        "ROUND(DEGREES(ST_AZIMUTH(ST_STARTPOINT(a.the_geom), ST_ENDPOINT(a.the_geom)))) AS ANG_L, "+
                                        "ROUND(DEGREES(ST_AZIMUTH(ST_STARTPOINT(ST_ROTATE(a.the_geom, pi()/2)), "+
                                        "ST_ENDPOINT(ST_ROTATE(a.the_geom, pi()/2))))) AS ANG_H FROM $build_min_rec a  "+
                                        "LEFT JOIN $buildingTableName b ON a.$idFieldBu=b.$idFieldBu").toString())

                    // The angles are transformed in the [0, 180]° interval
                    datasource.execute(("DROP TABLE IF EXISTS $build_dir180; CREATE TABLE $build_dir180 AS "+
                                        "SELECT $idFieldBl, LEN_L, LEN_H, CASEWHEN(ANG_L>=180, ANG_L-180, ANG_L) AS ANG_L, "+
                                        "CASEWHEN(ANG_H>180, ANG_H-180, ANG_H) AS ANG_H FROM $build_dir360").toString())

                    // The query aiming to create the building direction distribution is created
                    String sqlQueryDist = "DROP TABLE IF EXISTS $build_dir_dist; CREATE TABLE $build_dir_dist AS SELECT "
                    for (int i=angleRangeSize; i<180; i+=angleRangeSize){
                        sqlQueryDist += "SUM(CASEWHEN(ANG_L>=${i-angleRangeSize} AND ANG_L<$i, LEN_L, " +
                                        "CASEWHEN(ANG_H>=${i-angleRangeSize} AND ANG_H<$i, LEN_H, 0))) AS ANG$i, "
                    }
                    sqlQueryDist += "$idFieldBl FROM $build_dir180 GROUP BY $idFieldBl;"
                    datasource.execute(sqlQueryDist)

                    // The total of building linear of direction is calculated for each block
                    String sqlQueryTot = "DROP TABLE IF EXISTS $build_dir_tot; CREATE TABLE $build_dir_tot AS SELECT *, "
                    for (int i=angleRangeSize; i<180; i+=angleRangeSize){
                        sqlQueryTot += "ANG$i + "
                    }
                    sqlQueryTot = sqlQueryTot[0..-3] + "AS ANG_TOT FROM $build_dir_dist;"
                    datasource.execute(sqlQueryTot)

                    // The Perkings Skill score is finally calculated using a last query
                    String sqlQueryPerkins = "DROP TABLE IF EXISTS $outputTableName; CREATE TABLE $outputTableName AS SELECT $idFieldBl, "
                    for (int i=angleRangeSize; i<180; i+=angleRangeSize){
                        sqlQueryPerkins += "LEAST(1./(${180/angleRangeSize}), ANG$i::float/ANG_TOT) + "
                    }
                    sqlQueryPerkins = sqlQueryPerkins[0..-3] + "AS block_perkins_skill_score_building_direction" +
                                        " FROM $build_dir_tot;"
                    datasource.execute(sqlQueryPerkins)

                    // The temporary tables are deleted
                    datasource.execute(("DROP TABLE IF EXISTS $build_min_rec, $build_dir360, $build_dir180, "+
                                        "$build_dir_dist, $build_dir_tot").toString())

                    [outputTableName: outputTableName]
                }
            }
    )}

/**
 * This process calculates the ratio between the area of hole within a block and the area of the block.
 *
 * @param datasource A connexion to a database (H2GIS, PostGIS, ...) where are stored the input Table and in which
 * the resulting database will be stored
 * @param blockTable the name of the input ITable where are stored the block geometries
 * @param prefixName String use as prefix to name the output table
 *
 * @return outputTableName Table name in which the block id and their corresponding indicator value are stored
 * @author Jérémy Bernard
 */
static IProcess holeAreaDensity() {
    return processFactory.create(
            "Hole area ratio",
            [blockTable: String, prefixName: String, datasource: JdbcDataSource],
            [outputTableName : String],
            { blockTable, prefixName, datasource ->
                def geometricField = "the_geom"
                def idColumnBl = "id_block"

                // The name of the outputTableName is constructed
                String baseName = "block_hole_area_density"
                String outputTableName = prefixName + "_" + baseName

                String query = "DROP TABLE IF EXISTS $outputTableName; CREATE TABLE $outputTableName AS " +
                        "SELECT $idColumnBl, ST_AREA(ST_HOLES($geometricField))/ST_AREA($geometricField) " +
                        "AS $baseName FROM $blockTable"

                logger.info("Executing $query")
                datasource.execute query
                [outputTableName: outputTableName]
            }
    )}

/**
 * This indicator is usefull for the urban fabric classification proposed in Thornay et al. (2017) and also described
 * in Bocher et al. (2018). It answers to the Step 11 of the manual decision tree which consists in checking whether
 * the block is closed (continuous buildings the aligned along road). In order to identify the RSU with closed blocks,
 * the difference  between the st_holes(bloc scale) and sum(st_holes(building scale)) indicators will be calculated.
 *
 * WARNING: this method will not be able to identify blocks that are nearly closed (e.g. 99 % of the RSU perimeter).
 *
 * References:
 *   Bocher, E., Petit, G., Bernard, J., & Palominos, S. (2018). A geoprocessing framework to compute
 * urban indicators: The MApUCE tools chain. Urban climate, 24, 153-174.
 *   Tornay, Nathalie, Robert Schoetter, Marion Bonhomme, Serge Faraut, and Valéry Masson. "GENIUS: A methodology to
 * define a detailed description of buildings for urban climate and building energy consumption simulations."
 * Urban Climate 20 (2017): 75-93.
 *
 * @param datasource A connexion to a database (H2GIS, PostGIS, ...) where are stored the input Table and in which
 * the resulting database will be stored
 * @param buildTable the name of the input ITable where are stored the geometry field and the block ID
 * @param correlationTableName the name of the input ITable where are stored the relationships between blocks and buildings
 * @param prefixName String use as prefix to name the output table
 *
 * @return outputTableName Table name in which the block id and their corresponding indicator value are stored
 * @author Jérémy Bernard
 */
static IProcess closingness() {
    return processFactory.create(
            "Closingness of a block",
            [correlationTableName: String, blockTable: String, prefixName: String, datasource: JdbcDataSource],
            [outputTableName : String],
            { correlationTableName, blockTable, prefixName, datasource ->
                def geometryFieldBu = "the_geom"
                def geometryFieldBl = "the_geom"
                def idColumnBl = "id_block"

                // The name of the outputTableName is constructed
                String baseName = "block_closingness"
                String outputTableName = prefixName + "_" + baseName

                String query = "CREATE INDEX IF NOT EXISTS id_bubl ON $blockTable($idColumnBl); " +
                        "CREATE INDEX IF NOT EXISTS id_b ON $correlationTableName($idColumnBl); " +
                        "DROP TABLE IF EXISTS $outputTableName;" +
                        " CREATE TABLE $outputTableName AS SELECT b.$idColumnBl, " +
                        "ST_AREA(ST_HOLES(b.$geometryFieldBl))-SUM(ST_AREA(ST_HOLES(a.$geometryFieldBu))) AS $baseName " +
                        "FROM $correlationTableName a, $blockTable b WHERE a.$idColumnBl = b.$idColumnBl " +
                        "GROUP BY b.$idColumnBl"

                logger.info("Executing $query")
                datasource.execute query
                [outputTableName: outputTableName]
            }
    )}