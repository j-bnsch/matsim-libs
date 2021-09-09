package org.matsim.contrib.shifts.dispatcher;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.shifts.fleet.ShiftDvrpVehicle;
import org.matsim.contrib.shifts.operationFacilities.OperationFacility;
import org.matsim.contrib.shifts.schedule.ShiftBreakTask;
import org.matsim.contrib.shifts.shift.DrtShift;
import org.matsim.core.events.MobsimScopeEventHandler;

/**
 * @author nkuehnel, fzwick
 */
public interface DrtShiftDispatcher extends MobsimScopeEventHandler {

	final class ShiftEntry {
		public final DrtShift shift;
		public final ShiftDvrpVehicle vehicle;

		public ShiftEntry(DrtShift shift, ShiftDvrpVehicle vehicle) {
			this.shift = shift;
			this.vehicle = vehicle;
		}
	}

    void dispatch(double timeStep);

    OperationFacility decideOnBreak(ShiftEntry activeShift);

    void endShift(ShiftDvrpVehicle vehicle, Id<Link> id);

    void endBreak(ShiftDvrpVehicle vehicle, ShiftBreakTask task);

    void startBreak(ShiftDvrpVehicle vehicle, Id<Link> linkId);
}
