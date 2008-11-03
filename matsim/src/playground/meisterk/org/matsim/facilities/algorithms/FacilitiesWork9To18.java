/* *********************************************************************** *
 * project: org.matsim.*
 * FacilitiesWork9To18.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2007 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package playground.meisterk.org.matsim.facilities.algorithms;

import java.util.Iterator;
import java.util.TreeMap;

import org.matsim.facilities.Activity;
import org.matsim.facilities.Facilities;
import org.matsim.facilities.Facility;
import org.matsim.facilities.Opentime;

import playground.meisterk.org.matsim.enterprisecensus.EnterpriseCensus;
import playground.meisterk.org.matsim.enterprisecensus.EnterpriseCensusParser;

public class FacilitiesWork9To18 {

	private EnterpriseCensus myCensus;

	public FacilitiesWork9To18() {
		super();
	}

	public void run(Facilities facilities) {

		int hectareCnt = 0, facilityCnt = 0;
		int skip = 1;
		int B01S2, B01S3, B01EQTS2, B01EQTS3, jobsPerFacility;
		Integer reli; 
		String X, Y;
		Facility f;
		Activity a;

		System.out.println("  creating EnterpriseCensus object... ");
		this.myCensus = new EnterpriseCensus();
		System.out.println("  done.");

		System.out.println("  reading enterprise census files into EnterpriseCensus object... ");
		EnterpriseCensusParser myCensusParser = new EnterpriseCensusParser(this.myCensus);
		try {
			myCensusParser.parse(this.myCensus);
		} catch (Exception e) {
			e.printStackTrace();
		}
		System.out.println("  done.");

		System.out.println("  creating facilities... ");
		TreeMap<Integer, TreeMap<String, Double>> hectareAggregation = this.myCensus.getHectareAggregation();
//		TreeMap<String, double[]> ecHectars = this.myCensus.getEnterpriseCensusHectareAggregation();
		Iterator<Integer> it = hectareAggregation.keySet().iterator();
		while (it.hasNext()) {
			reli = it.next();
			B01S2 = (int) this.myCensus.getHectareAggregationInformation(reli, "B01S2");
			B01S3 = (int) this.myCensus.getHectareAggregationInformation(reli, "B01S3");
			B01EQTS2 = (int) Math.round(this.myCensus.getHectareAggregationInformation(reli, "B01EQTS2"));
			B01EQTS3 = (int) Math.round(this.myCensus.getHectareAggregationInformation(reli, "B01EQTS3"));
			X = Integer.toString((int) this.myCensus.getHectareAggregationInformation(reli, "X"));
			Y = Integer.toString((int) this.myCensus.getHectareAggregationInformation(reli, "Y"));

			for (int i=0; i<B01S2; i++) {
				f = facilities.createFacility(Integer.toString(facilityCnt++), X, Y);
				a = f.createActivity("work");

				// equally distribute jobs among facilities
				// as a test here, not exactly the number of avail workplaces :-)
				// in the data, the number of fulltime job equivalents is >0, but the rounding might be ==0
				// but there has to be at least one job
				jobsPerFacility = Math.max(B01EQTS2 / B01S2, 1);
				a.setCapacity(jobsPerFacility);

				a.addOpentime(new Opentime("wk", "9:00", "18:00"));
			}

			for (int i=0; i<B01S3; i++) {
				f = facilities.createFacility(Integer.toString(facilityCnt++), X, Y);
				a = f.createActivity("work");

				// equally distribute jobs among facilities
				// as a test here, not exactly the number of avail workplaces :-)
				// in the data, the number of fulltime job equivalents is >0, but the rounding might be ==0
				// but there has to be at least one job
				jobsPerFacility = Math.max(B01EQTS3 / B01S3, 1);
				a.setCapacity(jobsPerFacility);

				a.addOpentime(new Opentime("wk", "9:00", "18:00"));
			}

			hectareCnt++;
			//System.out.println("\t\t\tProcessed " + hectareCnt + " hectares.");
			if ((hectareCnt % skip) == 0) {
				System.out.println("\t\t\tProcessed " + hectareCnt + " hectares.");
				skip *= 2;
			}
		}
		System.out.println("  creating facilities...DONE.");

//		System.out.println("  writing EnterpriseCensus object to output file... ");
//		EnterpriseCensusWriter myCensusWriter = new EnterpriseCensusWriter();
//		try {
//			myCensusWriter.write(this.myCensus);
//		} catch (Exception e) {
//			e.printStackTrace();
//		}
//		System.out.println("  done.");
	}

}
