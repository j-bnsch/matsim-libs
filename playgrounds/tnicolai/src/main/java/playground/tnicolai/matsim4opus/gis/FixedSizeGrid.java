package playground.tnicolai.matsim4opus.gis;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.Map;

import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkImpl;
import org.matsim.core.utils.geometry.CoordImpl;
import org.matsim.core.utils.io.IOUtils;

import playground.tnicolai.matsim4opus.constants.Constants;
import playground.tnicolai.matsim4opus.utils.UtilityCollection;
import playground.tnicolai.matsim4opus.utils.helperObjects.AccessibilityStorage;
import playground.tnicolai.matsim4opus.utils.helperObjects.NetworkBoundary;

public class FixedSizeGrid {
	
	private static final Logger logger = Logger.getLogger(FixedSizeGrid.class);
	
	private AccessibilityStorage[][] grid;
	
	private double minX;
	private double maxX;
	private double minY;
	private double maxY;
	private int rowPoints;
	private int colPoints;
	
	private int coarseningSteps;
	private double resolution;
	
	public FixedSizeGrid(final double resolutionMeter, final NetworkImpl network, final Map<Id, AccessibilityStorage> resultMap, int coarseningSteps){
		
		logger.info("Initializing Grid ...");
		
		if(coarseningSteps < 0){
			logger.warn("Detected a negative number of steps to coarse the grid! Setting number of steps to zero!");
			this.coarseningSteps = 0;
		}
		else
			this.coarseningSteps = coarseningSteps;
		
		this.resolution = resolutionMeter;
		
		assert(network != null);
		NetworkBoundary boundary = UtilityCollection.getNetworkBoundary(network);
		
		this.minX = boundary.getMinX();
		this.minY = boundary.getMinY();
		this.rowPoints = (int)Math.ceil( boundary.getYLength() / resolutionMeter ) + 1;
		this.colPoints = (int)Math.ceil( boundary.getXLength() / resolutionMeter ) + 1;
		this.maxX = minX + ((colPoints - 1) * resolutionMeter);
		this.maxY = minY + ((rowPoints - 1) * resolutionMeter);
		
		logger.info("Determined area:");
		logger.info("Y Min: " + this.minY);
		logger.info("Y Max: " + this.maxY + "(this extended from " + boundary.getMaxY() + ").");
		logger.info("X Min: " + this.minX);
		logger.info("X Max: " + this.maxX + "(this extended from " + boundary.getMaxX() + ").");
		
		logger.info("Create Grid with " + colPoints + " columns and " + rowPoints + " rows ...");
		grid = new AccessibilityStorage[rowPoints][colPoints];
		
		double xCoord;
		double yCoord;
		assert(resultMap != null);
		
		for(int col = 0; col < colPoints; col++){
			
			xCoord = minX + (col * resolutionMeter);
			
			for(int row = 0; row < rowPoints; row++){
				
				yCoord = minY + (row * resolutionMeter);

				// create coordinate from current x, y values
				Coord coordinate = new CoordImpl(xCoord, yCoord);
				// get corresponding nearest network Node
				Node node = network.getNearestNode(coordinate);
				
				// set accessibility values (AccessibilityStorage object)
				grid[row][col] = resultMap.get(node.getId());
			}
		}
		
		logger.info("Done initializing Grid!");
	}
	
	public void writeGrid(){
		for(int coarseFactor = 0; coarseFactor <= this.coarseningSteps; coarseFactor++)
			write(coarseFactor);
	}
	
	private void write(int coarseFactor){
		
		logger.info("Writing accessibility matrix with coarse factor = " + coarseFactor);
		double currResolution = Math.pow(2, coarseFactor) * this.resolution;
		logger.info("The matrix has a relolution of " + currResolution + " meter.");
		
		try{
			BufferedWriter ttWriter = IOUtils.getBufferedWriter(Constants.MATSIM_4_OPUS_TEMP + currResolution + Constants.ERSA_TRAVEL_TIME_ACCESSIBILITY + Constants.FILE_TYPE_CSV);
			
			// writing x coordinates (header)
			for(int col = 0; (col < colPoints) && (col % Math.pow(2, coarseFactor)) == 0; col++ ){
				
				// determine x coord
				double xCoord = this.minX + (col * currResolution);
				
				ttWriter.write("\t");
				ttWriter.write( String.valueOf( xCoord ));
			}
			ttWriter.newLine();
			
			// writing accessibility values row by row with corresponding y-coordinates in first column (as header)
			for(int row = rowPoints - 1; (row >= 0 ) && (row % Math.pow(2, coarseFactor)) == 0; row--){
				
				//determine y coord
				double yCoord = this.maxY - (row * currResolution);
				// writing y-coordinates (header)
				ttWriter.write( String.valueOf( yCoord ) );
				
				
				for(int col = 0; (col < colPoints) && (col % Math.pow(2, coarseFactor)) == 0; col++ ){
					ttWriter.write("\t");
					ttWriter.write( String.valueOf( grid[row][col].getTravelTimeAccessibility() ));
//					System.out.println("col: " + col + " row: " + row);
				}
				ttWriter.newLine();
			}
			ttWriter.flush();
			ttWriter.close();
		}
		catch(IOException ioe){
			ioe.printStackTrace();
		}
	}

	public static void main(String args[]){
		
		for(int i = 0; i < 30; i++){
			for(int f = 1; f < 5; f++){
			System.out.println("i=" + i + " with modulo (2**"+f+")= " + (i % (Math.pow(2., f))));
			}
			System.out.println("---");
		}
	}
}
