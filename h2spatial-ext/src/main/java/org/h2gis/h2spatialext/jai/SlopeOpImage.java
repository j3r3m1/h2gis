/**
 * H2GIS is a library that brings spatial support to the H2 Database Engine
 * <http://www.h2database.com>.
 *
 * H2GIS is distributed under GPL 3 license. It is produced by CNRS
 * <http://www.cnrs.fr/>.
 *
 * H2GIS is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * H2GIS is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * H2GIS. If not, see <http://www.gnu.org/licenses/>.
 *
 * For more information, please consult: <http://www.h2gis.org/>
 * or contact directly: info_at_h2gis.org
 */
package org.h2gis.h2spatialext.jai;

import org.h2.util.RasterUtils;

import javax.media.jai.BorderExtender;
import javax.media.jai.ImageLayout;
import java.awt.Point;
import java.awt.image.RenderedImage;
import java.util.Map;

/**
 * Slope operation on raster
 * @author Nicolas Fortin
 * @author Erwan Bocher
 */
public class SlopeOpImage extends Area3x3OpImage {
    private enum UnitType {PERCENT, DEGREE, RADIANT};
    private final UnitType unit;
    private RasterUtils.RasterMetaData metaData;
    // Distance matrix of neighbors pixels. Take account of skewX and skewY
    private final double[] distanceMatrix;
    private final double[] invDistanceMatrix;
    private final static double FACTOR = 180 / Math.PI;


    public SlopeOpImage(RenderedImage source, RasterUtils.RasterMetaData metaData, BorderExtender extender,
            Map config, ImageLayout
            layout, String unitName) {
        // Require 1 neighbors around the source pixel
        super(source, extender, config, layout);
        this.unit = UnitType.valueOf(unitName);
        this.metaData = metaData;
        distanceMatrix = new double[NEIGHBORS_INDEX.length];
        invDistanceMatrix = new double[NEIGHBORS_INDEX.length];
        int index = 0;
        for(Point pos : NEIGHBORS_INDEX) {
            distanceMatrix[index] = metaData.getPixelCoordinate(0, 0).distance(metaData.getPixelCoordinate(pos.x,
                    pos.y));
            invDistanceMatrix[index] = 1 / distanceMatrix[index];
            index++;
        }
    }

    @Override
    protected double computeCell(int i, int j, int band, double cellValue, double[] neighborsValues) {
        final double noDataValue = metaData.bands[band].noDataValue;
        if(Double.compare(cellValue, noDataValue) != 0) {
            double maxSlope = Double.MIN_VALUE;
            for (int idNeigh = 0; idNeigh < neighborsValues.length; idNeigh++) {
                if(Double.compare(neighborsValues[idNeigh], noDataValue) != 0) {
                    double heightRatio = (cellValue - neighborsValues[idNeigh]) * invDistanceMatrix[idNeigh];
                    maxSlope = Math.max(maxSlope, heightRatio);
                }
            }
            return maxSlope;
        } else {
            return metaData.bands[band].noDataValue;
        }
    }
}
