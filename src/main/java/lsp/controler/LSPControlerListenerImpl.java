/*
 *  *********************************************************************** *
 *  * project: org.matsim.*
 *  * *********************************************************************** *
 *  *                                                                         *
 *  * copyright       : (C) 2022 by the members listed in the COPYING,        *
 *  *                   LICENSE and WARRANTY file.                            *
 *  * email           : info at matsim dot org                                *
 *  *                                                                         *
 *  * *********************************************************************** *
 *  *                                                                         *
 *  *   This program is free software; you can redistribute it and/or modify  *
 *  *   it under the terms of the GNU General Public License as published by  *
 *  *   the Free Software Foundation; either version 2 of the License, or     *
 *  *   (at your option) any later version.                                   *
 *  *   See also COPYING, LICENSE and WARRANTY file                           *
 *  *                                                                         *
 *  * ***********************************************************************
 */

package lsp.controler;


import lsp.*;
import lsp.replanning.LSPReplanningModule;
import lsp.LSPCarrierResource;
import lsp.scoring.LSPScoringModule;
import lsp.shipment.LSPShipment;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.freight.carrier.Carrier;
import org.matsim.contrib.freight.carrier.Carriers;
import org.matsim.contrib.freight.controler.CarrierAgentTracker;
import org.matsim.contrib.freight.events.eventsCreator.LSPEventCreator;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.controler.MatsimServices;
import org.matsim.core.controler.events.*;
import org.matsim.core.controler.listener.*;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.events.handler.EventHandler;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;


class LSPControlerListenerImpl implements BeforeMobsimListener, AfterMobsimListener, ScoringListener,
							  ReplanningListener, IterationStartsListener{
	private static final Logger log = Logger.getLogger( LSPControlerListenerImpl.class );

	private CarrierAgentTracker carrierResourceTracker;
	private final Carriers carriers;
	private final Scenario scenario;
	private final LSPReplanningModule replanningModule;
	private final LSPScoringModule scoringModule;
	private final Collection<LSPEventCreator> creators;

	private List<EventHandler> registeredHandlers = new ArrayList<>();

	@Inject private EventsManager eventsManager;
	@Inject private MatsimServices matsimServices;

	@Inject LSPControlerListenerImpl( Scenario scenario, LSPReplanningModule replanningModule, LSPScoringModule scoringModule,
					  Collection<LSPEventCreator> creators ) {
		this.scenario = scenario;
		this.replanningModule = replanningModule;
		this.scoringModule = scoringModule;
		this.creators = creators;
		this.carriers = getCarriers();
	}

	@Override
	public void notifyBeforeMobsim(BeforeMobsimEvent event) {
		LSPs lsps = LSPUtils.getLSPs( scenario );

		LSPRescheduler.notifyBeforeMobsim(lsps, event);

		carrierResourceTracker = new CarrierAgentTracker(carriers, creators, eventsManager );
		eventsManager.addHandler(carrierResourceTracker);
//		registeredHandlers = new ArrayList<>();

		for(LSP lsp : lsps.getLSPs().values()) {

			// simulation trackers of lsp:
			registerEventHandlers( lsp );

			// simulation trackers of shipments:
			for(LSPShipment shipment : lsp.getShipments()) {
				registerEventHandlers( shipment );
			}

			LSPPlan selectedPlan = lsp.getSelectedPlan();

			// simulation trackers of solutions:
			for(LogisticsSolution solution : selectedPlan.getSolutions()) {
				registerEventHandlers( solution );

				// simulation trackers of solution elements:
				for(LogisticsSolutionElement element : solution.getSolutionElements()) {
					registerEventHandlers( element );

					// simulation trackers of resources:
					registerEventHandlers( element.getResource() );

				}
			}
		}
	}
	private void registerEventHandlers( HasSimulationTrackers<?> lsp ){
		// get all simulation trackers ...
		for( LSPSimulationTracker<?> simulationTracker : lsp.getSimulationTrackers() ){
			// ... register themselves ...
			if ( !registeredHandlers.contains( simulationTracker ) ){
				log.warn("adding eventsHandler: " + simulationTracker );
				eventsManager.addHandler( simulationTracker );
				registeredHandlers.add( simulationTracker );
				matsimServices.addControlerListener( simulationTracker );
			} else {
				log.warn("not adding eventsHandler since already added: " + simulationTracker );
			}
			// ... and register all attached event handlers:
//			for( EventHandler eventHandler : simulationTracker.getEventHandlers() ){
//				if ( !registeredHandlers.contains( eventHandler ) ){
//					eventsManager.addHandler( eventHandler );
//					registeredHandlers.add( eventHandler );
//				}
//			}
		}
	}


	//Hier muss noch die Moeglichkeit reinkommen, dass nicht alle LSPs nach jeder Iteration neu planen, sondern nur ein Teil von denen
	//Das kann durch ein entsprechendes replanningModule erreicht werden. Hier muss man dann nix aendern
	@Override
	public void notifyReplanning(ReplanningEvent event) {
		replanningModule.replanLSPs(event);
	}

	@Override
	public void notifyScoring(ScoringEvent event) {
		scoringModule.scoreLSPs(event);
	}

	@Override
	public void notifyAfterMobsim(AfterMobsimEvent event) {
//		LSPs lsps = LSPUtils.getLSPs( scenario );

		eventsManager.removeHandler(carrierResourceTracker);

//		Collection<LSPSimulationTracker> alreadyUpdatedTrackers = new ArrayList<>();
//		for(LSP lsp : lsps.getLSPs().values()) {

//			for( LSPSimulationTracker<LSP> simulationTracker : lsp.getSimulationTrackers() ){
//				simulationTracker.notifyAfterMobsim( event );
//			}

//			for(LogisticsSolution solution : lsp.getSelectedPlan().getSolutions()) {
//				for(LogisticsSolutionElement solutionElement : solution.getSolutionElements()) {
//					for( LSPSimulationTracker<LSPResource> tracker : solutionElement.getResource().getSimulationTrackers()) {
//						if(!alreadyUpdatedTrackers.contains(tracker)) {
//							tracker.notifyAfterMobsim(event);
//							alreadyUpdatedTrackers.add(tracker);
//						}
//					}
//					for( LSPSimulationTracker<LogisticsSolutionElement> tracker : solutionElement.getSimulationTrackers()) {
//						tracker.notifyAfterMobsim(event);
//					}
//				}
//				for( LSPSimulationTracker tracker : solution.getSimulationTrackers()) {
//					tracker.notifyAfterMobsim(event);
//				}
//			}
//		}

//		for(LSP lsp : lsps.getLSPs().values()) {
//			for(LogisticsSolution solution : lsp.getSelectedPlan().getSolutions()) {
//				for(LogisticsSolutionElement element : solution.getSolutionElements()) {
//					for(LSPInfo info : element.getAttributes()) {
//						info.update();
//					}
//				}
//				for(LSPInfo info : solution.getAttributes()) {
//					info.update();
//				}
//			}
//		}
	}


	private Carriers getCarriers() {
		LSPs lsps = LSPUtils.getLSPs( scenario );

		Carriers carriers = new Carriers();
		for(LSP lsp : lsps.getLSPs().values()) {
			LSPPlan selectedPlan = lsp.getSelectedPlan();
			for(LogisticsSolution solution : selectedPlan.getSolutions()) {
				for(LogisticsSolutionElement element : solution.getSolutionElements()) {
					if(element.getResource() instanceof LSPCarrierResource) {

						LSPCarrierResource carrierResource = (LSPCarrierResource) element.getResource();
						Carrier carrier = carrierResource.getCarrier();
						if(!carriers.getCarriers().containsKey(carrier.getId())) {
							carriers.addCarrier(carrier);
						}
					}
				}
			}
		}
		return carriers;
	}

	public CarrierAgentTracker getCarrierResourceTracker() {
		return carrierResourceTracker;
	}

	@Override
	public void notifyIterationStarts(IterationStartsEvent event) {
//		LSPs lsps = LSPUtils.getLSPs( scenario );

//		if(event.getIteration() > 0) {
//			for(EventHandler handler : registeredHandlers) {
//				eventsManager.removeHandler(handler);
//			}

//			for(LSP lsp : lsps.getLSPs().values()) {
//				for( LSPSimulationTracker<LSP> simulationTracker : lsp.getSimulationTrackers() ){
//					simulationTracker.reset( );
//				}
//				for(LSPShipment shipment : lsp.getShipments()) {
//					shipment.clearSimulationTrackers();
//				}
//				for(LogisticsSolution solution : lsp.getSelectedPlan().getSolutions()) {
//					for(EventHandler handler : solution.getSimulationTrackers()) {
//						handler.reset(event.getIteration());
//					}
//					for( LSPSimulationTracker tracker : solution.getSimulationTrackers()) {
//						tracker.reset();
//					}
//					for(LogisticsSolutionElement element : solution.getSolutionElements()) {
//						for(EventHandler handler : element.getSimulationTrackers()) {
//							handler.reset(event.getIteration());
//						}
//						for( LSPSimulationTracker tracker : element.getSimulationTrackers()) {
//							tracker.reset();
//						}
//						for(EventHandler handler : element.getResource().getSimulationTrackers()) {
//							handler.reset(event.getIteration());
//						}
//						for( LSPSimulationTracker tracker : element.getResource().getSimulationTrackers()) {
//							tracker.reset();
//						}
//					}
//				}
//			}

//		}
	}
}
