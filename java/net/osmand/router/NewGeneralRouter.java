package net.osmand.router;

import gnu.trove.list.array.TByteArrayList;
import gnu.trove.list.array.TIntArrayList;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteRegion;
import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteTypeRule;
import net.osmand.binary.RouteDataObject;
import net.osmand.router.BinaryRoutePlanner.RouteSegment;
import net.osmand.router.GeneralRouter.GeneralRouterProfile;
import net.osmand.util.MapUtils;

public class NewGeneralRouter extends VehicleRouter {
	
	private RouteAttributeContext[] objectAttributes;
	public Map<String, String> attributes;
	
	private Map<String, RoutingParameter> parameters = new LinkedHashMap<String, RoutingParameter>(); 
	private Map<String, Integer> universalRules = new LinkedHashMap<String, Integer>();
	private List<String> universalRulesById = new ArrayList<String>(); 
//	private Map<String, List<RouteAttributeEvalRule>> freeTagRules = new HashMap<String, List<RouteAttributeEvalRule>>();
	
	private Map<RouteRegion, Map<Integer, Integer>> regionConvert = new LinkedHashMap<RouteRegion, Map<Integer,Integer>>();
	

	// cached values
	private boolean restrictionsAware = true;
	private float leftTurn;
	private float roundaboutTurn;
	private float rightTurn;
	private float minDefaultSpeed = 10;
	private float maxDefaultSpeed = 10;
	
	public enum RouteDataObjectAttribute {
		ROAD_SPEED("speed"),
		ROAD_PRIORITIES("priority"),
		ACCESS("access"),
		OBSTACLES("obstacle_time"),
		ROUTING_OBSTACLES("obstacle"),
		ONEWAY("oneway");
		public final String nm; 
		RouteDataObjectAttribute(String name) {
			nm = name;
		}
		
		public static RouteDataObjectAttribute getValueOf(String s){
			for(RouteDataObjectAttribute a : RouteDataObjectAttribute.values()){
				if(a.nm.equals(s)){
					return a;
				}
			}
			return null;
		}
	}
	
	public enum RoutingParameterType {
		NUMERIC,
		BOOLEAN,
		SYMBOLIC
	}
	
	public NewGeneralRouter(GeneralRouterProfile profile, Map<String, String> attributes) {
		this.attributes = new LinkedHashMap<String, String>();
		Iterator<Entry<String, String>> e = attributes.entrySet().iterator();
		while(e.hasNext()){
			Entry<String, String> next = e.next();
			addAttribute(next.getKey(), next.getValue());
		}
		objectAttributes = new RouteAttributeContext[RouteDataObjectAttribute.values().length];
		for(int i =0; i<objectAttributes.length; i++) {
			objectAttributes[i] = new RouteAttributeContext();
		}
	}
	public NewGeneralRouter(NewGeneralRouter parent, Map<String, Object> params ) {
		this.attributes = new LinkedHashMap<String, String>();
		Iterator<Entry<String, String>> e = parent.attributes.entrySet().iterator();
		while (e.hasNext()) {
			Entry<String, String> next = e.next();
			addAttribute(next.getKey(), next.getValue());
		}
		universalRules = parent.universalRules;
		
		objectAttributes = new RouteAttributeContext[RouteDataObjectAttribute.values().length];
		for(int i =0; i<objectAttributes.length; i++) {
			objectAttributes[i] = parent.objectAttributes[i].parameterize(params);
		}
	}


	public void addAttribute(String k, String v) {
		attributes.put(k, v);
		if(k.equals("restrictionsAware")) {
			restrictionsAware = parseSilentBoolean(v, restrictionsAware);
		} else if(k.equals("leftTurn")) {
			leftTurn = parseSilentFloat(v, leftTurn);
		} else if(k.equals("rightTurn")) {
			rightTurn = parseSilentFloat(v, rightTurn);
		} else if(k.equals("roundaboutTurn")) {
			roundaboutTurn = parseSilentFloat(v, roundaboutTurn);
		} else if(k.equals("minDefaultSpeed")) {
			minDefaultSpeed = parseSilentFloat(v, minDefaultSpeed * 3.6f) / 3.6f;
		} else if(k.equals("maxDefaultSpeed")) {
			maxDefaultSpeed = parseSilentFloat(v, maxDefaultSpeed * 3.6f) / 3.6f;
		}
	}
	
	public RouteAttributeContext getObjContext(RouteDataObjectAttribute a) {
		return objectAttributes[a.ordinal()];
	}
	

	public void registerBooleanParameter(String id, String name, String description) {
		RoutingParameter rp = new RoutingParameter();
		rp.name = name;
		rp.description = description;
		rp.id = id;
		rp.type = RoutingParameterType.BOOLEAN;
		parameters.put(rp.id, rp);
		
	}

	public void registerNumericParameter(String id, String name, String description, Double[] vls, String[] vlsDescriptions) {
		RoutingParameter rp = new RoutingParameter();
		rp.name = name;
		rp.description = description;
		rp.id = id;
		rp.possibleValues = vls;
		rp.possibleValueDescriptions = vlsDescriptions;
		rp.type = RoutingParameterType.NUMERIC;
		parameters.put(rp.id, rp);		
	}

	@Override
	public boolean acceptLine(RouteDataObject way) {
		int res = getObjContext(RouteDataObjectAttribute.ACCESS).evaluateInt(way, 0);
		return res >= 0;
	}
	
	private int registerTagValueAttribute(String tag, String value) {
		String key = tag +"$"+value;
		if(universalRules.containsKey(key)) {
			return universalRules.get(key);
		}
		int id = universalRules.size();
		universalRulesById.add(key);
		universalRules.put(key, id);
//		updateFreeTagsWithNewRule(tag, id, false);
//		updateFreeTagsWithNewRule("-"+tag, id, true);
		return id;
	}
	/*
	private void updateFreeTagsWithNewRule(String tag, int id, boolean not) {
		List<RouteAttributeEvalRule> list = freeTagRules.get(tag);
		if (list != null) {
			for (RouteAttributeEvalRule r : list) {
				r.insertType(id, not);
			}
		}
	}
	
	private void registerFreeTagRule(RouteAttributeEvalRule r, String tag, boolean not) {
		String tagId = not ? "-" + tag : tag; 
		if(!freeTagRules.containsKey(tagId)) {
			freeTagRules.put(tagId, new ArrayList<NewGeneralRouter.RouteAttributeEvalRule>());
		}
		freeTagRules.get(tagId).add(r);
		// update rule for already registered types
		Iterator<Entry<String, Integer>> it = universalRules.entrySet().iterator();
		while (it.hasNext()) {
			Entry<String, Integer> e = it.next();
			String key = e.getKey();
			if (key.startsWith(tag + "$")) {
				r.insertType(e.getValue(), not);
			}
		}
	}
	*/
	
	@Override
	public NewGeneralRouter specialization(Map<String, Object> params) {
		return new NewGeneralRouter(this, params);
	}
	
	

	@Override
	public boolean restrictionsAware() {
		return restrictionsAware;
	}
	
	@Override
	public float defineObstacle(RouteDataObject road, int point) {
		int[] pointTypes = road.getPointTypes(point);
		if(pointTypes != null) {
			return getObjContext(RouteDataObjectAttribute.OBSTACLES).evaluateFloat(road.region, pointTypes, 0);
		}
		return 0;
	}
	
	@Override
	public float defineRoutingObstacle(RouteDataObject road, int point) {
		int[] pointTypes = road.getPointTypes(point);
		if(pointTypes != null){
			return getObjContext(RouteDataObjectAttribute.ROUTING_OBSTACLES).evaluateFloat(road.region, pointTypes, 0);
		}
		return 0;
	}
	
	@Override
	public int isOneWay(RouteDataObject road) {
		return getObjContext(RouteDataObjectAttribute.ONEWAY).evaluateInt(road, 0);
	}

	
	private static boolean parseSilentBoolean(String t, boolean v) {
		if (t == null || t.length() == 0) {
			return v;
		}
		return Boolean.parseBoolean(t);
	}

	private static float parseSilentFloat(String t, float v) {
		if (t == null || t.length() == 0) {
			return v;
		}
		return Float.parseFloat(t);
	}
	
	@Override
	public float defineSpeed(RouteDataObject road) {
		return getObjContext(RouteDataObjectAttribute.ROAD_SPEED) .evaluateFloat(road, getMinDefaultSpeed() * 3.6f) / 3.6f;
	}

	@Override
	public float defineSpeedPriority(RouteDataObject road) {
		return getObjContext(RouteDataObjectAttribute.ROAD_PRIORITIES).evaluateFloat(road, 1f);
	}

	@Override
	public float getMinDefaultSpeed() {
		return minDefaultSpeed;
	}

	@Override
	public float getMaxDefaultSpeed() {
		return maxDefaultSpeed ;
	}

	
	public double getLeftTurn() {
		return leftTurn;
	}
	
	public double getRightTurn() {
		return rightTurn;
	}
	public double getRoundaboutTurn() {
		return roundaboutTurn;
	}
	@Override
	public double calculateTurnTime(RouteSegment segment, int segmentEnd, RouteSegment prev, int prevSegmentEnd) {
		int[] pt = prev.getRoad().getPointTypes(prevSegmentEnd);
		if(pt != null) {
			RouteRegion reg = prev.getRoad().region;
			for(int i=0; i<pt.length; i++) {
				RouteTypeRule r = reg.quickGetEncodingRule(pt[i]);
				if("highway".equals(r.getTag()) && "traffic_signals".equals(r.getValue())) {
					// traffic signals don't add turn info 
					return 0;
				}
			}
		}
		double rt = getRoundaboutTurn();
		if(rt > 0 && !prev.getRoad().roundabout() && segment.getRoad().roundabout()) {
			return rt;
		}
		if (getLeftTurn() > 0 || getRightTurn() > 0) {
			double a1 = segment.getRoad().directionRoute(segment.getSegmentStart(), segment.getSegmentStart() < segmentEnd);
			double a2 = prev.getRoad().directionRoute(prevSegmentEnd, prevSegmentEnd < prev.getSegmentStart());
			double diff = Math.abs(MapUtils.alignAngleDifference(a1 - a2 - Math.PI));
			// more like UT
			if (diff > 2 * Math.PI / 3) {
				return getLeftTurn();
			} else if (diff > Math.PI / 2) {
				return getRightTurn();
			}
			return 0;
		}
		return 0;
	}
	

	@Override
	public boolean containsAttribute(String attribute) {
		return attributes.containsKey(attribute);
	}
	
	@Override
	public String getAttribute(String attribute) {
		return attributes.get(attribute);
	}
	
	
	public static class RoutingParameter {
		private String id;
		private String name;
		private String description;
		private RoutingParameterType type;
		private Object[] possibleValues;
		private String[] possibleValueDescriptions;
		
		public String getId() {
			return id;
		}
		
		public String getName() {
			return name;
		}
		public String getDescription() {
			return description;
		}
		public RoutingParameterType getType() {
			return type;
		}
		public String[] getPossibleValueDescriptions() {
			return possibleValueDescriptions;
		}
		
		public Object[] getPossibleValues() {
			return possibleValues;
		}
	}
	
	
	
	public class RouteAttributeContext {
		List<RouteAttributeEvalRule> rules = new ArrayList<RouteAttributeEvalRule>();
		
		public Object evaluate(RouteDataObject ro) {
			int[] types = convert(ro.region, ro.types);
			return evaluate(types);
		}

		public void printRules(PrintStream out) {
			for(RouteAttributeEvalRule r : rules) {
				r.printRule(out);
			}
		}

		public RouteAttributeContext parameterize(Map<String, Object> params ) {
			RouteAttributeContext c = new RouteAttributeContext();
			for(RouteAttributeEvalRule r : rules) {
				RouteAttributeEvalRule nr = new RouteAttributeEvalRuleParameterized(r, params);
				c.rules.add(nr);
			}
			return c;
		}
		
		public RouteAttributeEvalRule registerNewRule(String selectValue) {
			RouteAttributeEvalRule ev = new RouteAttributeEvalRule();
			ev.registerSelectValue(selectValue);
			rules.add(ev);	
			return ev;
		}
		
		public RouteAttributeEvalRule getLastRule() {
			return rules.get(rules.size() - 1);
		}

		private Object evaluate(int[] types) {
			for (int k = 0; k < rules.size(); k++) {
				Object o = rules.get(k).eval(types);
				if (o != null) {
					return o;
				}
			}
			return null;
		}
		
		public int evaluateInt(RouteDataObject ro, int defValue) {
			Object o = evaluate(ro);
			if(o == null) {
				return defValue;
			}
			return ((Number)o).intValue();
		}
		
		public int evaluateInt(RouteRegion region, int[] types, int defValue) {
			Object o = evaluate(convert(region, types));
			if(o == null) {
				return defValue;
			}
			return ((Number)o).intValue();
		}
		
		public float evaluateFloat(RouteDataObject ro, float defValue) {
			Object o = evaluate(ro);
			if(o == null) {
				return defValue;
			}
			return ((Number)o).floatValue();
		}
		
		public float evaluateFloat(RouteRegion region, int[] types, float defValue) {
			Object o = evaluate(convert(region, types));
			if(o == null) {
				return defValue;
			}
			return ((Number)o).floatValue();
		}
		
		private int[] convert(RouteRegion reg, int[] types) {
			int[] utypes = new int[types.length];
			Map<Integer, Integer> map = regionConvert.get(reg);
			if(map == null){
				map = new HashMap<Integer, Integer>();
				regionConvert.put(reg, map);
			}
			for(int k = 0; k < types.length; k++) {
				Integer nid = map.get(types[k]);
				if(nid == null){
					RouteTypeRule r = reg.quickGetEncodingRule(types[k]);
					nid = registerTagValueAttribute(r.getTag(), r.getValue());
					map.put(types[k], nid);
				}
				utypes[k] = nid;
			}
			Arrays.sort(utypes);
			return utypes;
		}
		
	}
	
	
	private class RouteAttributeEvalRuleParameterized extends RouteAttributeEvalRule {
		private boolean parameterValue = true;
		private Map<String, Object> params;
		public RouteAttributeEvalRuleParameterized(RouteAttributeEvalRule r, Map<String, Object> params) {
			this.params = params;
			parameters = r.parameters;
			selectValue = r.selectValue;
			notType = r.notType;
			sortedTypeArrays = r.sortedTypeArrays;
			onlyTags = r.onlyTags;
			onlyNonTags = r.onlyNonTags;
			parameterValue = true;
			for (String p : r.parameters) {
				boolean not = false;
				if (p.startsWith("-")) {
					not = true;
					p = p.substring(1);
				}
				boolean val = false;
				if (params.containsKey(p)) {
					Object v = params.get(p);
					val = v instanceof Boolean && ((Boolean) v).booleanValue();
				}
				if (not && val) {
					parameterValue = false;
					break;
				} else if (!not && !val) {
					parameterValue = false;
					break;
				}
			}
		}
		
		public Map<String, Object> getParamValues() {
			return params;
		}
		
		public Object eval(int[] types) {
			if (!parameterValue) {
				return null;
			}
			return super.eval(types);
		}

	}

	

	public class RouteAttributeEvalRule {
		
		protected List<String> parameters = new ArrayList<String>() ;
		protected Object selectValue = null;
		protected TByteArrayList notType = new TByteArrayList();
		protected TIntArrayList sortedTypeArrays = new TIntArrayList();
		protected Set<String> onlyTags = new LinkedHashSet<String>();
		protected Set<String> onlyNonTags = new LinkedHashSet<String>();
		
		public void registerSelectValue(String value) {
			try {
				selectValue = Double.parseDouble(value);
			} catch (NumberFormatException e) {
				System.err.println("TODO parse value " + value);
			}
		}
		
		public void printRule(PrintStream out) {
			out.print(" Select " + selectValue + " if ");
			for(int k = 0; k < sortedTypeArrays.size(); k++) {
				String key = universalRulesById.get(sortedTypeArrays.get(k));
				out.print(key + " ");
			}
			for(int k = 0; k < parameters.size(); k++) {
				out.print(" param="+parameters.get(k));
			}
			if(onlyTags.size() > 0) {
				out.print(" match tag = " + onlyTags);
			}
			if(onlyNonTags.size() > 0) {
				out.print(" not match tag = " + onlyNonTags);
			}
			out.println();
		}

		public void registerAndTagValueCondition(String tag, String value, boolean not) {
			if(value == null) { 
				if (not) {
					onlyNonTags.add(tag);
				} else {
					onlyTags.add(tag);
				}
			} else {
				int vtype = registerTagValueAttribute(tag, value);
				insertType(vtype, not);
			}
		}
		
		public void registerAndParamCondition(String param, boolean not) {
			param = not ? "-" + param : param;
			parameters.add(param);
		}

		public void insertType(int t, boolean not) {
			int i = sortedTypeArrays.binarySearch(t);
			if (i < 0) {
				sortedTypeArrays.insert(-(i + 1), t);
				notType.insert(-(i + 1), (byte) (not ? 1 : -1));
			}
		}
		
		public Object eval(int[] types){
			if(matches(types)){
				return selectValue;
			}
			return null;
		}

		public boolean matches(int[] types) {
			int t = 0;
			for(int k = 0; k < sortedTypeArrays.size(); k++) {
				int valType = sortedTypeArrays.get(k);
				while(t < types.length && types[t] < valType) {
					t++;
				}
				if(t >= types.length || types[t] != valType) {
					if(notType.get(k) < 0) {
						return false;
					}
				} else /*types[t] == valType*/{
					if(notType.get(k) > 0) {
						return false;
					}
				}
			}
			if(onlyTags.size() > 0 || onlyNonTags.size() > 0) {
				int onlyTagcount = 0;
				for (int j = 0; j < types.length; j++) {
					String tagValue = universalRulesById.get(types[j]);
					String tag = tagValue.substring(0, tagValue.indexOf('$'));
					if(onlyTags.contains(tag)) {
						onlyTagcount ++;
					}
					if(onlyNonTags.contains(tag)) {
						return false;
					}
				}
				if(onlyTagcount < onlyTags.size()) {
					return false;
				}
			}
			return true;
		}
		
	}
}

