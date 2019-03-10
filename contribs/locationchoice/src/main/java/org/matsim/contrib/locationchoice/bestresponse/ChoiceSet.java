/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2012 by the members listed in the COPYING,        *
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

package org.matsim.contrib.locationchoice.bestresponse;

import java.util.*;

import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contrib.locationchoice.DestinationChoiceConfigGroup;
import org.matsim.contrib.locationchoice.DestinationChoiceConfigGroup.ApproximationLevel;
import org.matsim.contrib.locationchoice.router.BackwardFastMultiNodeDijkstra;
import org.matsim.core.config.groups.PlansCalcRouteConfigGroup;
import org.matsim.core.gbl.Gbl;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.*;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.scoring.ScoringFunctionFactory;
import org.matsim.core.utils.misc.Time;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.facilities.ActivityFacility;
import org.matsim.facilities.FacilitiesUtils;

class ChoiceSet {
	private static final Logger log = Logger.getLogger( ChoiceSet.class ) ;

	private final Network network;
	private final DestinationChoiceConfigGroup dccg;
	private ApproximationLevel approximationLevel;
	private List<Id<ActivityFacility>> destinations = new LinkedList<>();
	private List<Id<ActivityFacility>> notYetVisited = new LinkedList<>();
	private final ActivityFacilities facilities;
	private final Scenario scenario;
	private final Map<String, Double> teleportedModeSpeeds;
	private final Map<String, Double> beelineDistanceFactors;
	private final boolean reUsePlans;

	private final Map<Id<ActivityFacility>, Id<Link>> nearestLinks;
	private MultiNodeDijkstra forwardMultiNodeDijkstra;
	private BackwardFastMultiNodeDijkstra backwardMultiNodeDijkstra;
	private TripRouter tripRouter;

	@Override
	public String toString() {
		StringBuilder stb = new StringBuilder() ;
		stb.append("destinations:") ;
		for ( Id<ActivityFacility> id : destinations ) {
			stb.append( " " ).append( id );
		}
		stb.append("\n") ;
		stb.append("notYetVisited:" ) ;
		for ( Id<ActivityFacility> id : notYetVisited ) {
			stb.append( " " ).append( id );
		}
		return stb.toString() ;
	}

	ChoiceSet(ApproximationLevel approximationLevel, Scenario scenario, Map<Id<ActivityFacility>, Id<Link>> nearestLinks,
		    Map<String, Double> teleportedModeSpeeds, Map<String, Double> beelineDistanceFactors) {
		this.approximationLevel = approximationLevel;
		this.facilities = scenario.getActivityFacilities();
		this.scenario = scenario;
		this.nearestLinks = nearestLinks;
		this.teleportedModeSpeeds = teleportedModeSpeeds;
		this.beelineDistanceFactors = beelineDistanceFactors;

		this.dccg = (DestinationChoiceConfigGroup) this.scenario.getConfig().getModule(DestinationChoiceConfigGroup.GROUP_NAME);
		this.reUsePlans = dccg.getReUseTemporaryPlans();

		this.network = scenario.getNetwork() ;
	}

	void addDestination(Id<ActivityFacility> facilityId) {
		this.destinations.add(facilityId);
		this.notYetVisited.add(facilityId);
	}

	Id<ActivityFacility> getWeightedRandomChoice( int actlegIndex,
								    ScoringFunctionFactory scoringFunction, Plan plan, TripRouter tripRouter, double pKVal,
								    MultiNodeDijkstra forwardMultiNodeDijkstra,
								    BackwardFastMultiNodeDijkstra backwardMultiNodeDijkstra, int iteration ) {
		this.forwardMultiNodeDijkstra = forwardMultiNodeDijkstra;
		this.backwardMultiNodeDijkstra = backwardMultiNodeDijkstra;
		this.tripRouter = tripRouter ;

		List<ScoredAlternative> list;

		if (this.destinations.size() > 0) {
			list = this.createReducedChoiceSetWithPseudoScores(actlegIndex, this.facilities, scoringFunction, plan,
				  tripRouter );
		} else {
			// if we have no destinations defined so far, we can shorten this
			// currently handled activity which should be re-located
			Activity act = (Activity) plan.getPlanElements().get(actlegIndex);
			//			list = createEmptyChoiceMap( act.getFacilityId() );
			list = Collections.singletonList( new ScoredAlternative( 0., act.getFacilityId() ) ) ;
			// (the "0" is a dummy entry!)
		}

		Random random = new Random((long) (Long.MAX_VALUE / iteration * pKVal));

		//	a couple of random draws to come to the "chaotic" region:
		for (int i = 0; i < 10; i++) {
			random.nextDouble();
		}
		int randomIndex = random.nextInt( list.size() ) ;

		return list.get( randomIndex ).getAlternativeId() ;

	}

	/**
	 * The "score", which is behind the "Double" in the TreeMap, is some pseudo score 0.6, 0.84, ..., see {ChoiceSet#generateReducedChoiceSet(ArrayList)}.
	 * Well, no, not any more, just setting all of them to 0.2.
	 */
	private List<ScoredAlternative> createReducedChoiceSetWithPseudoScores(
		  int actlegIndex,
		  ActivityFacilities facilities,
		  ScoringFunctionFactory scoringFunction,
		  Plan plan,
		  TripRouter router ) {

		// currently handled activity which should be re-located
		Activity activityToRelocate = (Activity) plan.getPlanElements().get(actlegIndex);

		List<InitialNode> destinationNodes = new ArrayList<>();

		// We need to calculate the multi node dijkstra stuff only in case localRouting is used.
		if (this.approximationLevel == DestinationChoiceConfigGroup.ApproximationLevel.localRouting )
		{
			// we want to investigate multiple destinations for a given activity.  Thus, we need
			// (1) the Dijkstra tree from the activity before to all these destinations
			// (2) the (backwards) Dijkstra tree from all these destinations to the activity following the given activity.
			// The two trees will then be passed into the (preliminary) scoring of these destinations, where they will be evaluated for the
			// destination under consideration.

			// (0) collect all possible destinations and copy them into an "imaginary" node.
			for( Id<ActivityFacility> destinationId : this.destinations ){
				ActivityFacility destinationFacility = this.scenario.getActivityFacilities().getFacilities().get( destinationId );
				Link destinationLink = FacilitiesUtils.decideOnLink( destinationFacility, network );
				;
				//				Id<Link> linkId = destinationFacility.getLinkId();
				//				Link destinationLink;
				//				if (linkId != null) {
				//					destinationLink = this.network.getLinks().get(linkId);
				//				} else {
				//					destinationLink = NetworkUtils.getNearestLink( this.network, destinationFacility.getCoord() );
				//				}

				Node toNode = Objects.requireNonNull( destinationLink ).getToNode();

				//				log.info( "destinationToNode=" + toNode.getId() ) ;

				InitialNode initialToNode = new InitialNode( toNode, 0.0, 0.0 );
				destinationNodes.add( initialToNode );
			}
			ImaginaryNode destinationNode = MultiNodeDijkstra.createImaginaryNode( destinationNodes );

			// (1) forward tree
			{

				//			Activity previousActivity = ((PlanImpl)plan).getPreviousActivity(((PlanImpl)plan).getPreviousLeg(activityToRelocate));
				Leg previousLeg = LCPlanUtils.getPreviousLeg( plan, activityToRelocate );
				Activity previousActivity = LCPlanUtils.getPreviousActivity( plan, previousLeg );
				Node nextActNode = this.network.getLinks().get( PopulationUtils.decideOnLinkIdForActivity( previousActivity, scenario ) ).getToNode();

				forwardMultiNodeDijkstra.setSearchAllEndNodes( true );
				forwardMultiNodeDijkstra.calcLeastCostPath( nextActNode, destinationNode, previousActivity.getEndTime(), plan.getPerson(), null );
			}

			// (2) backward tree
			{
				//			Activity nextActivity = ((PlanImpl)plan).getNextActivity(((PlanImpl)plan).getNextLeg(activityToRelocate));
				Leg nextLeg = LCPlanUtils.getNextLeg( plan, activityToRelocate );
				Activity nextActivity = LCPlanUtils.getNextActivity( plan, nextLeg );
				Node nextActNode = this.network.getLinks().get( PopulationUtils.decideOnLinkIdForActivity( nextActivity, scenario ) ).getToNode();

				/*
				 * The original code below uses the relocated activities end time as start time. Does this make sense?
				 * Probably yes, since the trip to the next destination is short??
				 * BUT: if we use that activities end time, we could also use another ForwardMultiNodeDijsktra...
				 * Switched to nextActivity.startTime() since this time is also available in PlanTimesAdapter.computeTravelTimeFromLocalRouting()
				 * where the path's created by the Dijkstra are used. So far (I think), the estimated start times
				 * were used there (leastCostPathCalculatorBackward.setEstimatedStartTime(activityToRelocate.getEndTime())).
				 *
				 * cdobler oct'13
				 */
				// yy but the code that follows now is not doing what the comment above says, or does it?  kai, mar'19
				backwardMultiNodeDijkstra.setSearchAllEndNodes( true );
				backwardMultiNodeDijkstra.calcLeastCostPath( nextActNode, destinationNode, activityToRelocate.getEndTime(), plan.getPerson(), null );

				//		BackwardDijkstraMultipleDestinations leastCostPathCalculatorBackward = new BackwardDijkstraMultipleDestinations(network, travelCost, travelTime);
				//		leastCostPathCalculatorBackward.setEstimatedStartTime(activityToRelocate.getEndTime());
				//		// the backwards Dijkstra will expand from the _next_ activity location backwards to all locations in the system.  This is the time
				//		// at which this is anchored.  (Clearly too early, but probably not that bad as an approximation.)
			}
			// ---
		}

		ArrayList<ScoredAlternative> list = new ArrayList<ScoredAlternative>();
		double largestValue = Double.NEGATIVE_INFINITY;
		Id<ActivityFacility> facilityIdWithLargestScore = activityToRelocate.getFacilityId();

		/*
		 * TODO:
		 * Can this be merged with the for loop above? So far, facilities are looked up twice.
		 * cdobler, oct'13
		 * I cannot say what this comment refers to.  Maybe this has been done in the meantime? kai, mar'19
		 */

		Plan planTmp = null;

		// In case we try to re-use a single copy of the plan: create the copy here and re-use it within the loop.
		if (this.reUsePlans) planTmp = LCPlanUtils.createCopy(plan );

		for (Id<ActivityFacility> destinationId : this.destinations) {
			// tentatively set
			ActivityFacility facility = facilities.getFacilities().get(destinationId);

			// As far as I can see, activity location is updated in the plan. Then the routes from and to that activity 
			// are calculated. The resulting travel times are written to the temporary plan. If this is true, it should 
			// not be necessary to update the activity location in the copied plan? I am not sure about this, therefore 
			// keep the update in the "if(this.ReUsePlans)" block. cdobler oct'15
			LCPlanUtils.setFacilityId(activityToRelocate, destinationId );
			LCPlanUtils.setCoord(activityToRelocate, facility.getCoord() );
			LCPlanUtils.setLinkId(activityToRelocate, this.nearestLinks.get(destinationId ) );

			//			PlanImpl planTmp = new PlanImpl();
			//			planTmp.copyFrom(plan);

			if (this.reUsePlans) {
				// we have to update the copied plan
				Activity actTmp = (Activity) planTmp.getPlanElements().get(actlegIndex);
				LCPlanUtils.setFacilityId(actTmp, destinationId );
				LCPlanUtils.setCoord(actTmp, facility.getCoord() );
				LCPlanUtils.setLinkId(actTmp, this.nearestLinks.get(destinationId ) );
			}
			// If we don't re-use a single copy of the plan, create a new one.
			else planTmp = LCPlanUtils.createCopy(plan );

			switch ( dccg.getTravelTimeApproximationLevel() ){
				case completeRouting:
				case noRouting:
					throw new RuntimeException( "currently not implemented" );
				case localRouting:{
					Node movedActNode;
					{
						Id<Link> movedActLinkId = PopulationUtils.decideOnLinkIdForActivity( activityToRelocate, scenario );
						Link movedActLink = scenario.getNetwork().getLinks().get( movedActLinkId );
						movedActNode = movedActLink.getToNode();
					}
					{
						Node prevActNode;
						double startTime;
						final Leg previousLeg = LCPlanUtils.getPreviousLeg( plan, activityToRelocate );
						{
							final Activity prevAct = LCPlanUtils.getPreviousActivity( plan, previousLeg );
							Id<Link> linkId = PopulationUtils.decideOnLinkIdForActivity( prevAct, scenario );
							Link link = scenario.getNetwork().getLinks().get( linkId );
							prevActNode = link.getToNode();

							startTime = PlanRouter.calcEndOfActivity( prevAct, plan, scenario.getConfig() );
						}

						LeastCostPathCalculator.Path result = this.forwardMultiNodeDijkstra.constructPath( prevActNode, movedActNode, startTime );
						previousLeg.setTravelTime( result.travelTime );
					}
					{
						Node nextActNode;
						final Leg nextLeg = LCPlanUtils.getNextLeg( plan, activityToRelocate );
						{
							final Activity nextAct = LCPlanUtils.getNextActivity( plan, nextLeg );
							Id<Link> linkId = PopulationUtils.decideOnLinkIdForActivity( nextAct, scenario );
							Link link = scenario.getNetwork().getLinks().get( linkId );
							nextActNode = link.getToNode();
						}
						double startTime = PlanRouter.calcEndOfActivity( activityToRelocate, plan, scenario.getConfig() );

						LeastCostPathCalculator.Path result = this.backwardMultiNodeDijkstra.constructPath( movedActNode, nextActNode, startTime );
						nextLeg.setTravelTime( result.travelTime );
					}
				}
				break ;
				default:
					throw new RuntimeException( Gbl.NOT_IMPLEMENTED ) ;
			}
			PlanTimesAdapter adapter = new PlanTimesAdapter( router.getStageActivityTypes(), this.scenario );
			final double score = adapter.scorePlan( planTmp, scoringFunction, plan.getPerson() );

			if (score > largestValue) {
				largestValue = score;
				facilityIdWithLargestScore = destinationId;
			}
			list.add(new ScoredAlternative(score, destinationId));
		}

		Collections.sort( list );
		if ( !list.isEmpty() ) {
			return list.subList( 0,Math.min( list.size(), 5) ) ;
		}
		else  {
			/* how is this supposed to happen at all?  kai, jan'13
			 *
			 * If the choice set is empty (for example due to sampling) use the current activity location.
			 * TODO: add a mechanism to at least keep, a minimal number of alternatives (minimally the chosen
			 * location of the previous iteration. Then this "if" can go.
			 *
			 * yyyyyy If facilityIdWithLargestScore is defined, it is also in list and thus in mapCorrected.  No?  kai, feb'13
			 */
			//			TreeMap<Double,Id> mapTmp = new TreeMap<Double,Id>();
			//			mapTmp.put(1.1, facilityIdWithLargestScore);
			//			return mapTmp;
			//			return createEmptyChoiceMap(facilityIdWithLargestScore);
			return Collections.singletonList( new ScoredAlternative( largestValue, facilityIdWithLargestScore ) )  ;
		}
	}

	private List<? extends PlanElement> estimateTravelTime( Plan plan , Activity act ) {
		// Ouch... should be passed as parameter!
		final Leg previousLeg = LCPlanUtils.getPreviousLeg( plan, act );
		final Activity previousActivity = LCPlanUtils.getPreviousActivity( plan, previousLeg );
		final String mode = previousLeg.getMode();

		switch ( this.approximationLevel ) {
			case completeRouting:
				final List<? extends PlanElement> trip = this.tripRouter.calcRoute( mode, FacilitiesUtils.toFacility( previousActivity, scenario.getActivityFacilities() ),
					  FacilitiesUtils.toFacility( act, scenario.getActivityFacilities() ), previousActivity.getEndTime(), plan.getPerson() );
				fillInLegTravelTimes( previousActivity.getEndTime() , trip );
				return trip;
			case noRouting:
				// Yes, those two are doing the same. I passed some time to simplify the (rather convoluted) code,
				// and it boiled down to this. No idea of since how long this is not doing what it is claiming to do...
				// The only difference is that "noRouting" was getting travel times from the route for car if it exists
				// td dec 15
				if ( mode.equals( TransportMode.car ) && previousLeg.getTravelTime() != Time.UNDEFINED_TIME ) {
					return Collections.singletonList( previousLeg );
				}
				// fall through to local routing if not car or previous travel time not found
			case localRouting:
				return getTravelTimeApproximation( previousActivity, act, mode );
			default:
				throw new RuntimeException( "unknown method "+this.approximationLevel );
		}
	}

	private static void fillInLegTravelTimes(
		  final double departureTime,
		  final List<? extends PlanElement> trip ) {
		double time = departureTime;
		for ( PlanElement pe : trip ) {
			if ( !(pe instanceof Leg) ) continue;
			final Leg leg = (Leg) pe;
			if ( leg.getDepartureTime() == Time.UNDEFINED_TIME ) {
				leg.setDepartureTime( time );
			}
			if ( leg.getTravelTime() == Time.UNDEFINED_TIME ) {
				leg.setTravelTime( leg.getRoute().getTravelTime() );
			}
			time += leg.getTravelTime();
		}
	}

	private List<? extends PlanElement> getTravelTimeApproximation( Activity fromAct, Activity toAct, String mode ) {
		// TODO: as soon as plansCalcRoute provides defaults for all modes use them
		// I do not want users having to set dc parameters in other config modules!
		double speed;

		// Those two parameters could probably be defined with more flexibility...
		if ( mode.equals( TransportMode.pt )) {
			speed = this.dccg.getTravelSpeed_pt();
		}
		else if ( mode.equals( TransportMode.car ) ) {
			speed = this.dccg.getTravelSpeed_car();
		}
		else if ( mode.equals( TransportMode.bike )) {
			speed = this.teleportedModeSpeeds.get( TransportMode.bike );
		}
		else if ( mode.equals( TransportMode.walk ) || mode.equals( TransportMode.transit_walk )) {
			speed = this.teleportedModeSpeeds.get(TransportMode.walk);
		}
		else {
			speed = this.teleportedModeSpeeds.get( PlansCalcRouteConfigGroup.UNDEFINED );
		}

		// This is the only place where this class is used: delete it? td dec 15
		final PathCostsGeneric pathCosts = new PathCostsGeneric( network );
		pathCosts.createRoute(
			  this.network.getLinks().get( fromAct.getLinkId() ),
			  this.network.getLinks().get( toAct.getLinkId() ),
			  this.beelineDistanceFactors.get( PlansCalcRouteConfigGroup.UNDEFINED ),
			  speed );
		final Leg l = PopulationUtils.createLeg(mode );
		l.setRoute( pathCosts.getRoute() );
		l.setTravelTime( pathCosts.getRoute().getTravelTime() );
		l.setDepartureTime( fromAct.getEndTime() );
		return Collections.singletonList( l );
	}


}
