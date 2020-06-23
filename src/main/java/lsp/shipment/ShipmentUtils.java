package lsp.shipment;

import demand.utilityFunctions.UtilityFunction;
import lsp.LogisticsSolutionElement;
import lsp.functions.Info;
import lsp.resources.Resource;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.freight.carrier.TimeWindow;

import java.util.ArrayList;

public class ShipmentUtils{
	private ShipmentUtils(){} // do not instantiate

	public static class LSPShipmentBuilder{

		private Id<LSPShipment> id;
		private Id<Link> fromLinkId;
		private Id<Link> toLinkId;
		private TimeWindow startTimeWindow;
		private TimeWindow endTimeWindow;
		private int capacityDemand;
		private double serviceTime;
		private ArrayList<Requirement> requirements;
		private ArrayList<UtilityFunction> utilityFunctions;
		private ArrayList<Info> infos;

		public static LSPShipmentBuilder newInstance( Id<LSPShipment> id ){
			return new LSPShipmentBuilder(id);
		}

		private LSPShipmentBuilder( Id<LSPShipment> id ){
			this.requirements = new ArrayList<Requirement>();
			this.utilityFunctions = new ArrayList<UtilityFunction>();
			this.infos = new ArrayList<Info>();
			this.id = id;
		}

		public LSPShipmentBuilder setFromLinkId( Id<Link> fromLinkId ){
			this.fromLinkId = fromLinkId;
			return this;
		}

		public LSPShipmentBuilder setToLinkId( Id<Link> toLinkId ){
			this.toLinkId = toLinkId;
			return this;
		}

		public LSPShipmentBuilder setStartTimeWindow( TimeWindow startTimeWindow ){
			this.startTimeWindow = startTimeWindow;
			return this;
		}

		public LSPShipmentBuilder setEndTimeWindow( TimeWindow endTimeWindow ){
			this.endTimeWindow = endTimeWindow;
			return this;
		}

		public LSPShipmentBuilder setCapacityDemand( int capacityDemand ){
			this.capacityDemand = capacityDemand;
			return this;
		}

		public LSPShipmentBuilder setServiceTime( double serviceTime ){
			this.serviceTime = serviceTime;
			return this;
		}

		public LSPShipmentBuilder addRequirement( Requirement requirement ) {
			requirements.add(requirement);
			return this;
		}

		public LSPShipmentBuilder addUtilityFunction( UtilityFunction utilityFunction ) {
			utilityFunctions.add(utilityFunction);
			return this;
		}

		public LSPShipmentBuilder addInfo( Info info ) {
			infos.add(info);
			return this;
		}

		public LSPShipment build(){
			return new LSPShipmentImpl(this);
		}

		// --- Getters ---

		public Id<LSPShipment> getId() {
			return id;
		}

		public Id<Link> getFromLinkId() {
			return fromLinkId;
		}

		public Id<Link> getToLinkId() {
			return toLinkId;
		}

		public TimeWindow getStartTimeWindow() {
			return startTimeWindow;
		}

		public TimeWindow getEndTimeWindow() {
			return endTimeWindow;
		}

		public int getCapacityDemand() {
			return capacityDemand;
		}

		public double getServiceTime() {
			return serviceTime;
		}

		public ArrayList<Requirement> getRequirements() {
			return requirements;
		}

		public ArrayList<UtilityFunction> getUtilityFunctions() {
			return utilityFunctions;
		}

		public ArrayList<Info> getInfos() {
			return infos;
		}

	}

	public static class LoggedShipmentHandleBuilder {
		private double startTime;
		private double endTime;
		private LogisticsSolutionElement element;
		private Id<Resource> resourceId;
		private Id<Link> linkId;

		private LoggedShipmentHandleBuilder(){
		}

		public static LoggedShipmentHandleBuilder newInstance(){
			return new LoggedShipmentHandleBuilder();
		}

		public LoggedShipmentHandleBuilder setStartTime(double startTime){
			this.startTime = startTime;
			return this;
		}

		public LoggedShipmentHandleBuilder setEndTime(double endTime){
			this.endTime = endTime;
			return this;
		}

		public LoggedShipmentHandleBuilder setLogisticsSolutionElement(LogisticsSolutionElement element){
			this.element = element;
			return this;
		}

		public LoggedShipmentHandleBuilder setResourceId(Id<Resource> resourceId){
			this.resourceId = resourceId;
			return this;
		}

		public LoggedShipmentHandleBuilder setLinkId(Id<Link> linkId){
			this.linkId = linkId;
			return this;
		}

		public ShipmentPlanElement build(){
			return new LoggedShipmentHandle(this);
		}

		// --- Getters --- //

		public double getStartTime() {
			return startTime;
		}

		public double getEndTime() {
			return endTime;
		}

		public LogisticsSolutionElement getElement() {
			return element;
		}

		public Id<Resource> getResourceId() {
			return resourceId;
		}

		public Id<Link> getLinkId() {
			return linkId;
		}
	}

}
