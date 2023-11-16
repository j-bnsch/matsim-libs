/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2014 by the members listed in the COPYING,        *
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

package org.matsim.contrib.dvrp.passenger;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Route;
import org.matsim.contrib.dvrp.optimizer.Request;
import org.matsim.contrib.dvrp.optimizer.VrpOptimizer;
import org.matsim.contrib.dvrp.run.DvrpModes;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.mobsim.framework.MobsimDriverAgent;
import org.matsim.core.mobsim.framework.MobsimPassengerAgent;
import org.matsim.core.mobsim.framework.MobsimTimer;
import org.matsim.core.mobsim.framework.PlanAgent;
import org.matsim.core.mobsim.qsim.InternalInterface;
import org.matsim.core.modal.ModalProviders;

import com.google.common.base.Preconditions;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

/**
 * @author Michal Maciejewski (michalm)
 */
public final class DefaultPassengerEngine implements PassengerEngine, PassengerRequestRejectedEventHandler {

	private final String mode;
	private final MobsimTimer mobsimTimer;
	private final EventsManager eventsManager;

	private final PassengerRequestCreator requestCreator;
	private final VrpOptimizer optimizer;
	private final Network network;
	private final PassengerRequestValidator requestValidator;

	private final InternalPassengerHandling internalPassengerHandling;
	private final AdvanceRequestProvider advanceRequestProvider;

	private InternalInterface internalInterface;

	//accessed in doSimStep() and handleDeparture() (no need to sync)
	private final Map<Id<Request>, MobsimPassengerAgent> activePassengers = new HashMap<>();

	// holds vehicle stop activities for requests that have not arrived at departure point yet
	private final Map<Id<Request>, PassengerPickupActivity> waitingForPassenger = new HashMap<>();

	//accessed in doSimStep() and handleEvent() (potential data races)
	private final Queue<PassengerRequestRejectedEvent> rejectedRequestsEvents = new ConcurrentLinkedQueue<>();

	DefaultPassengerEngine(String mode, EventsManager eventsManager, MobsimTimer mobsimTimer, PassengerRequestCreator requestCreator,
		VrpOptimizer optimizer, Network network, PassengerRequestValidator requestValidator, AdvanceRequestProvider advanceRequestProvider) {
		this.mode = mode;
		this.mobsimTimer = mobsimTimer;
		this.requestCreator = requestCreator;
		this.optimizer = optimizer;
		this.network = network;
		this.requestValidator = requestValidator;
		this.eventsManager = eventsManager;
		this.advanceRequestProvider = advanceRequestProvider;

		internalPassengerHandling = new InternalPassengerHandling(mode, eventsManager);
	}

	@Override
	public void setInternalInterface(InternalInterface internalInterface) {
		this.internalInterface = internalInterface;
		internalPassengerHandling.setInternalInterface(internalInterface);
	}

	@Override
	public void onPrepareSim() {
	}

	@Override
	public void doSimStep(double time) {
		// If prebooked requests are rejected (by the optimizer, through an
		// event) after submission, but before departure, the PassengerEngine does not
		// know this agent yet. Hence, we wait with setting the state to abort until the
		// agent has arrived here (if ever).

		Iterator<PassengerRequestRejectedEvent> iterator = rejectedRequestsEvents.iterator();

		while (iterator.hasNext()) {
			PassengerRequestRejectedEvent event = iterator.next();
			if (event.getTime() == time) {
				// There is a potential race condition wrt processing rejection events between doSimStep() and handleEvent().
				// To ensure a deterministic behaviour, we only process events from the previous time step.
				break;
			}

			MobsimPassengerAgent passenger = activePassengers.remove(event.getRequestId());
			if (passenger != null) {
				// not much else can be done for immediate requests
				// set the passenger agent to abort - the event will be thrown by the QSim
				passenger.setStateToAbort(time);
				internalInterface.arrangeNextAgentState(passenger);
				iterator.remove();
			}
		}
	}

	@Override
	public void afterSim() {
	}

	@Override
	public boolean handleDeparture(double now, MobsimAgent agent, Id<Link> fromLinkId) {
		if (!agent.getMode().equals(mode)) {
			return false;
		}

		MobsimPassengerAgent passenger = (MobsimPassengerAgent)agent;
		internalInterface.registerAdditionalAgentOnLink(passenger);

		Id<Link> toLinkId = passenger.getDestinationLinkId();

		// try to find a prebooked requests that is associated to this leg
		Leg leg = (Leg)((PlanAgent)passenger).getCurrentPlanElement();
		PassengerRequest request = advanceRequestProvider.retrieveRequest(agent, leg);

		if (request == null) { // immediate request
			Route route = ((Leg)((PlanAgent)passenger).getCurrentPlanElement()).getRoute();
			request = requestCreator.createRequest(internalPassengerHandling.createRequestId(), passenger.getId(), route, getLink(fromLinkId),
				getLink(toLinkId), now, now);

			// must come before validateAndSubmitRequest (to come before rejection event)
			eventsManager.processEvent(new PassengerWaitingEvent(now, mode, request.getId(), request.getPassengerId()));
			activePassengers.put(request.getId(), passenger);

			validateAndSubmitRequest(passenger, request, now);
		} else { // advance request
			eventsManager.processEvent(new PassengerWaitingEvent(now, mode, request.getId(), request.getPassengerId()));
			activePassengers.put(request.getId(), passenger);

			PassengerPickupActivity pickupActivity = waitingForPassenger.remove(request.getId());
			if (pickupActivity != null) {
				// the vehicle is already waiting for the request, notify it
				pickupActivity.notifyPassengerIsReadyForDeparture(passenger, now);
			}
		}

		return true;
	}

	private void validateAndSubmitRequest(MobsimPassengerAgent passenger, PassengerRequest request, double now) {
		if (internalPassengerHandling.validateRequest(request, requestValidator, now)) {
			//need to synchronise to address cases where requestSubmitted() may:
			// - be called from outside DepartureHandlers
			// - interfere with VrpOptimizer.nextTask()
			// - impact VrpAgentLogic.computeNextAction()
			synchronized (optimizer) {
				//optimizer can also reject request if cannot handle it
				// (async operation, notification comes via the events channel)
				optimizer.requestSubmitted(request);
			}
		}
	}

	private Link getLink(Id<Link> linkId) {
		return Preconditions.checkNotNull(network.getLinks().get(linkId),
			"Link id=%s does not exist in network for mode %s. Agent departs from a link that does not belong to that network?", linkId, mode);
	}

	/**
	 * There are two ways of interacting with the PassengerEngine:
	 * <p>
	 * - (1) The stop activity tries to pick up a passenger and receives whether the
	 * pickup succeeded or not (see tryPickUpPassenger). In the classic
	 * implementation, the vehicle only calls tryPickUpPassenger at the time when it
	 * actually wants to pick up the person (at the end of the activity). It may
	 * happen that the person is not present yet. In that case, the pickup request
	 * is saved and notifyPassengerReady is called on the stop activity upen
	 * departure of the agent.
	 * <p>
	 * - (2) If pickup and dropoff times are handled more flexibly by the stop
	 * activity, it might want to detect whether an agent is ready to be picked up,
	 * then start an "interaction time" and only after perform the actual pickup.
	 * For that purpose, we have queryPickUpPassenger, which indicates whether the
	 * agent is already there, and, if not, makes sure that the stop activity is
	 * notified once the agent arrives for departure.
	 */
	@Override
	public boolean notifyWaitForPassenger(PassengerPickupActivity pickupActivity, MobsimDriverAgent driver, Id<Request> requestId) {
		if (!activePassengers.containsKey(requestId)) {
			waitingForPassenger.put(requestId, pickupActivity);
			return false;
		}

		return true;
	}

	@Override
	public boolean tryPickUpPassenger(PassengerPickupActivity pickupActivity, MobsimDriverAgent driver, Id<Request> requestId, double now) {
		return internalPassengerHandling.tryPickUpPassenger(driver, activePassengers.get(requestId), requestId, now);
	}

	@Override
	public void dropOffPassenger(MobsimDriverAgent driver, Id<Request> requestId, double now) {
		internalPassengerHandling.dropOffPassenger(driver, activePassengers.remove(requestId), requestId, now);
	}

	@Override
	public void handleEvent(PassengerRequestRejectedEvent event) {
		if (event.getMode().equals(mode)) {
			rejectedRequestsEvents.add(event);
		}
	}

	public static Provider<PassengerEngine> createProvider(String mode) {
		return new ModalProviders.AbstractProvider<>(mode, DvrpModes::mode) {
			@Inject
			private EventsManager eventsManager;

			@Inject
			private MobsimTimer mobsimTimer;

			@Override
			public DefaultPassengerEngine get() {
				return new DefaultPassengerEngine(getMode(), eventsManager, mobsimTimer, getModalInstance(PassengerRequestCreator.class),
					getModalInstance(VrpOptimizer.class), getModalInstance(Network.class), getModalInstance(PassengerRequestValidator.class),
					getModalInstance(AdvanceRequestProvider.class));
			}
		};
	}
}
