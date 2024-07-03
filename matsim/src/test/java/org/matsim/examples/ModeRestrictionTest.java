package org.matsim.examples;

import com.google.common.collect.Lists;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.LinkLeaveEvent;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent;
import org.matsim.api.core.v01.events.handler.LinkEnterEventHandler;
import org.matsim.api.core.v01.events.handler.LinkLeaveEventHandler;
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler;
import org.matsim.api.core.v01.events.handler.PersonLeavesVehicleEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.Config;
import org.matsim.core.config.groups.ControllerConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.testcases.MatsimTestUtils;
import org.matsim.vehicles.Vehicle;

import java.util.*;
import java.util.stream.Stream;

// @formatter:off
/**
 * In this test, we restrict the allowed modes of a link. The base case is the equil scenario.
 * <p>
 *       /...                   ...\
 * ------o------------o------------o-----------
 * l1  (n2)   l6    (n7)   l15  (n12)   l20
 */
// @formatter:on
public class ModeRestrictionTest {
	private static final Logger log = LogManager.getLogger(ModeRestrictionTest.class);

	@RegisterExtension
	private MatsimTestUtils utils = new MatsimTestUtils();

	static Stream<Arguments> provideArguments() {
		List<String> links = List.of("6", "15", "20");
		List<String> restrictedModes = List.of("car", "bike");

		return links.stream()
					.flatMap(link -> restrictedModes.stream().map(restrictedMode -> Arguments.of(link, restrictedMode)));
	}

	/**
	 * Setting: The agents (both car & bike) have a route from the plan. Link before and at the activity is restricted respectively. Consistency check is turned off.
	 * Expected behaviour: Since it is not checked, if the route contains link, which are not allowed to use anymore, it can still use the restricted link.
	 */
	@ParameterizedTest
	@MethodSource("provideArguments")
	void testNoRouteChange_ok(String link, String restrictedMode) {
		final Config config = prepareConfig("plans_act_link20.xml");

		Id<Link> linkId = Id.createLinkId(link);
		Scenario scenario = ScenarioUtils.loadScenario(config);
		Link networkLink = scenario.getNetwork().getLinks().get(linkId);
		removeModeFromLink(networkLink, restrictedMode);

		FirstLegVisitedLinksCheck firstLegRouteCheck = new FirstLegVisitedLinksCheck(
			Map.of(Id.createPersonId("car"), List.of(linkId), Id.createPersonId("bike"), List.of(linkId)));
		runController(scenario, firstLegRouteCheck);
		firstLegRouteCheck.assertActualContainsExpected();
	}

	/**
	 * Setting: The agents (both car & bike) have no route. The mode of the link in front of the activity is restricted. Consistency check is turned off.
	 * Expected behaviour: By default, the network is not cleaned. The router throws an exception since there is no route from home to the activity.
	 */
	@ParameterizedTest
	@ValueSource(strings = {"car", "bike"})
	void testRerouteBeforeSim_toActNotPossible_throws(String restrictedMode) {
		final Config config = prepareConfig("plans_act_link15.xml");

		Id<Link> link = Id.createLinkId("6");

		Scenario scenario = restrictLinkAndResetRoutes(config, link, restrictedMode);

		FirstLegVisitedLinksCheck firstLegRouteCheck = new FirstLegVisitedLinksCheck();
		RuntimeException exception = Assertions.assertThrows(RuntimeException.class, () -> runController(scenario, firstLegRouteCheck));
		Assertions.assertTrue(
			exception.getMessage().contains("Exception while processing persons. Cannot guarantee that all persons have been fully processed."));
	}

	/**
	 * Setting: The agents (both car & bike) have no route. The mode of the link behind the activity is restricted. Consistency check is turned off.
	 * Expected behaviour: By default, the network is not cleaned. The router throws an exception since there is no route from the activity to home.
	 */
	@ParameterizedTest
	@ValueSource(strings = {"car", "bike"})
	void testRerouteBeforeSim_fromActNotPossible_throws(String restrictedMode) {
		final Config config = prepareConfig("plans_act_link6.xml");

		Id<Link> link = Id.createLinkId("15");

		Scenario scenario = restrictLinkAndResetRoutes(config, link, restrictedMode);

		FirstLegVisitedLinksCheck firstLegRouteCheck = new FirstLegVisitedLinksCheck();
		RuntimeException exception = Assertions.assertThrows(RuntimeException.class, () -> runController(scenario, firstLegRouteCheck));
		Assertions.assertTrue(
			exception.getMessage().contains("Exception while processing persons. Cannot guarantee that all persons have been fully processed."));
	}

	/**
	 * Setting: The agents (both car & bike) have no route. The mode of the activity's link is restricted. Activity has x-y-coordinate. Consistency check is turned off.
	 * Expected behaviour: The agents (both car & bike) are rerouted to the nearest link to the activity's coordinate.
	 */
	@ParameterizedTest
	@ValueSource(strings = {"car", "bike"})
	void testRerouteBeforeSim_actOnRestrictedLinkWithCoords_ok(String restrictedMode) {
		final Config config = prepareConfig("plans_act_link15.xml");

		Scenario scenario = restrictLinkAndResetRoutes(config, Id.createLinkId("15"), restrictedMode);

		// New route. Note that the end link is 20, but the activity's link in the input was 15.
		// But since we restricted the mode for link 15, 20 is used as fallback.
		List<Id<Link>> newRoute = List.of(Id.createLinkId("1"), Id.createLinkId("2"), Id.createLinkId("11"), Id.createLinkId("20"));
		List<Id<Link>> oldRoute = List.of(Id.createLinkId("1"), Id.createLinkId("6"), Id.createLinkId("15"));

		String other = restrictedMode.equals("car") ? "bike" : "car";

		FirstLegVisitedLinksCheck firstLegRouteCheck = new FirstLegVisitedLinksCheck(Map.of(Id.createPersonId(restrictedMode), newRoute, Id.createPersonId(other), oldRoute));
		runController(scenario, firstLegRouteCheck);
		firstLegRouteCheck.assertActualEqualsExpected();
	}

	/**
	 * Setting: The agents (both car & bike) have no route. The mode of the activity's link is restricted. Activity has no x-y-coordinate. Consistency check is turned off.
	 * Expected behaviour: The router throws an exception since there is no fallback x-y-coordinate.
	 */
	@ParameterizedTest
	@ValueSource(strings = {"car", "bike"})
	void testRerouteBeforeSim_actOnRestrictedLinkWithoutCoord_throws(String restrictedMode){
		final Config config = prepareConfig("plans_act_link15.xml");

		Id<Person> person = Id.createPersonId(restrictedMode);
		Scenario scenario = restrictLinkAndResetRoutes(config, Id.createLinkId("15"), restrictedMode);
		Activity act = (Activity) scenario.getPopulation().getPersons().get(person).getPlans().getFirst().getPlanElements().get(2);
		act.setCoord(null);

		RuntimeException exception = Assertions.assertThrows(RuntimeException.class, () -> runController(scenario, new FirstLegVisitedLinksCheck()));
		Assertions.assertTrue(
			exception.getMessage().contains("Exception while processing persons. Cannot guarantee that all persons have been fully processed."));
	}

	private Config prepareConfig(String plansFile) {
		final Config config = utils.loadConfig(utils.getClassInputDirectory() + "config.xml");
		config.controller().setNetworkRouteConsistencyCheck(ControllerConfigGroup.NetworkRouteConsistencyCheck.disable);
		config.plans().setInputFile(plansFile);

		ScoringConfigGroup.ModeParams params = new ScoringConfigGroup.ModeParams("bike") ;
		config.scoring().addModeParams(params);

		config.qsim().setMainModes( new HashSet<>( Arrays.asList( TransportMode.car, TransportMode.bike ) ) ) ;
		config.routing().setNetworkModes( Arrays.asList( TransportMode.car, TransportMode.bike ) );
		config.routing().removeTeleportedModeParams("bike");

		config.controller().setLastIteration(0);
		return config;
	}

	private static Scenario restrictLinkAndResetRoutes(Config config, Id<Link> link, String restrictedMode) {
		Scenario scenario = ScenarioUtils.loadScenario(config);
		removeModeFromLink(scenario.getNetwork().getLinks().get(link), restrictedMode);
		for (Person person : scenario.getPopulation().getPersons().values()) {
			Leg leg = (Leg) person.getSelectedPlan().getPlanElements().get(1);
			leg.setRoute(null);
		}
		return scenario;
	}

	private static void runController(Scenario scenario, FirstLegVisitedLinksCheck firstLegRouteCheck) {
		Controler controler = new Controler(scenario);
		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				this.addEventHandlerBinding().toInstance(firstLegRouteCheck);
				this.addTravelTimeBinding(TransportMode.bike).to(BikeTravelTime.class);
			}
		});
		controler.run();
	}

	private static void removeModeFromLink(Link networkLink, String restrictedMode) {
		Set<String> allowedModes = new HashSet<>(networkLink.getAllowedModes());
		allowedModes.remove(restrictedMode);
		networkLink.setAllowedModes(allowedModes);
	}

	/**
	 * Event handler that checks the visited Links of a person on the first leg.
	 */
	private static class FirstLegVisitedLinksCheck implements LinkEnterEventHandler, LinkLeaveEventHandler, PersonEntersVehicleEventHandler,
		PersonLeavesVehicleEventHandler {
		private final Map<Id<Person>, List<Id<Link>>> expected = new HashMap<>();
		private final Map<Id<Person>, List<Id<Link>>> actual = new HashMap<>();
		private final Map<Id<Vehicle>, Id<Person>> currentPersonByVehicle = new HashMap<>();
		private final Map<Id<Person>, Boolean> onFirstLeg = new HashMap<>();

		public FirstLegVisitedLinksCheck() {
		}

		public FirstLegVisitedLinksCheck(Map<Id<Person>, List<Id<Link>>> expected) {
			this.expected.putAll(expected);
		}

		public FirstLegVisitedLinksCheck(Id<Person> personId, List<Id<Link>> expected) {
			this.expected.put(personId, expected);
		}

		public FirstLegVisitedLinksCheck(Id<Person> personId, Id<Link> expected) {
			this.expected.put(personId, Collections.singletonList(expected));
		}

		@Override
		public void handleEvent(LinkLeaveEvent event) {
			addLinkIdToActual(this.currentPersonByVehicle.get(event.getVehicleId()), event.getLinkId());
		}

		@Override
		public void handleEvent(LinkEnterEvent event) {
			addLinkIdToActual(this.currentPersonByVehicle.get(event.getVehicleId()), event.getLinkId());
		}

		@Override
		public void handleEvent(PersonLeavesVehicleEvent event) {
			this.currentPersonByVehicle.remove(event.getVehicleId());
			this.onFirstLeg.put(event.getPersonId(), false);
		}

		@Override
		public void handleEvent(PersonEntersVehicleEvent event) {
			this.currentPersonByVehicle.put(event.getVehicleId(), event.getPersonId());
			this.onFirstLeg.putIfAbsent(event.getPersonId(), true);
		}

		private void addLinkIdToActual(Id<Person> personId, Id<Link> linkId) {
			if (!this.onFirstLeg.get(personId)) {
				return;
			}
			actual.putIfAbsent(personId, Lists.newArrayList(linkId));
			List<Id<Link>> linkIds = actual.get(personId);
			if (linkIds.getLast() == null || linkIds.getLast() == linkId) {
				return;
			}
			linkIds.add(linkId);
		}

		public void assertActualEqualsExpected() {
			Assertions.assertEquals(expected, actual);
		}

		public void assertActualContainsExpected() {
			for (Map.Entry<Id<Person>, List<Id<Link>>> entry : expected.entrySet()) {
				Id<Person> personId = entry.getKey();
				List<Id<Link>> expected = entry.getValue();
				List<Id<Link>> actual = this.actual.get(personId);
				Assertions.assertNotNull(actual);
				Assertions.assertTrue(actual.containsAll(expected));
			}
		}
	}


	private static class BikeTravelTime implements TravelTime {
		@Override public double getLinkTravelTime( Link link, double time, Person person, Vehicle vehicle ){
			return 1. ;
		}
	}
}
