package org.matsim.contrib.freight.vrp.algorithms.rr;

public interface IterationStartListener extends RuinAndRecreateControlerListener{
	
	public void informIterationStarts(int iteration, RuinAndRecreateSolution currentSolution);

}
