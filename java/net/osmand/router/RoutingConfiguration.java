package net.osmand.router;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Stack;

import net.osmand.Collator;
import net.osmand.PlatformUtil;
import net.osmand.router.GeneralRouter.GeneralRouterProfile;
import net.osmand.router.NewGeneralRouter.RouteAttributeContext;
import net.osmand.router.NewGeneralRouter.RouteAttributeEvalRule;
import net.osmand.router.NewGeneralRouter.RouteDataObjectAttribute;
import net.osmand.util.Algorithms;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

public class RoutingConfiguration {
	
	public static final int DEFAULT_MEMORY_LIMIT = 30;
	public Map<String, String> attributes = new LinkedHashMap<String, String>();

	// 1. parameters of routing and different tweaks
	// Influence on A* : f(x) + heuristicCoefficient*g(X)
	public float heuristicCoefficient = 1;
	
	// 1.1 tile load parameters (should not affect routing)
	public int ZOOM_TO_LOAD_TILES = 16;
	public int memoryLimitation;

	// 1.2 Build A* graph in backward/forward direction (can affect results)
	// 0 - 2 ways, 1 - direct way, -1 - reverse way
	public int planRoadDirection = 0;

	// 1.3 Router specific coefficients and restrictions
	public VehicleRouter router = new NewGeneralRouter(GeneralRouterProfile.CAR, new LinkedHashMap<String, String>());
	public String routerName = "";
	
	// 1.4 Used to calculate route in movement
	public Double initialDirection;
	
	// 1.5 Recalculate distance help
	public float recalculateDistance = 10000f;
	


	public static class Builder {
		// Design time storage
		private String defaultRouter = "";
		private Map<String, VehicleRouter> routers = new LinkedHashMap<String, VehicleRouter>();
		private Map<String, String> attributes = new LinkedHashMap<String, String>();

		public RoutingConfiguration build(String router, int memoryLimitMB) {
			return build(router, null, memoryLimitMB, new String[0]);
		}
		
		public RoutingConfiguration build(String router, int memoryLimitMB, String[] specialization) {
			return build(router, null, memoryLimitMB, specialization);
		}
		public RoutingConfiguration build(String router, Double direction, int memoryLimitMB, String[] specialization) {
			if (!routers.containsKey(router)) {
				router = defaultRouter;
			}
			RoutingConfiguration i = new RoutingConfiguration();
			if (routers.containsKey(router)) {
				i.router = routers.get(router);
				if (specialization != null) {
					Map<String, Object> params = new LinkedHashMap<String, Object>();
					for (String s : specialization) {
						params.put(s, Boolean.TRUE);
					}
					i.router = i.router.specialization(params);
				}
				i.routerName = router;
			}
			attributes.put("routerName", router);
			i.attributes.putAll(attributes);
			i.initialDirection = direction;
			i.recalculateDistance = parseSilentFloat(getAttribute(i.router, "recalculateDistanceHelp"), i.recalculateDistance) ;
			i.heuristicCoefficient = parseSilentFloat(getAttribute(i.router, "heuristicCoefficient"), i.heuristicCoefficient);
			i.ZOOM_TO_LOAD_TILES = parseSilentInt(getAttribute(i.router, "zoomToLoadTiles"), i.ZOOM_TO_LOAD_TILES);
			int desirable = parseSilentInt(getAttribute(i.router, "memoryLimitInMB"), 0);
			if(desirable != 0) {
				i.memoryLimitation = desirable * (1 << 20); 
			} else {
				if(memoryLimitMB == 0) {
					memoryLimitMB = DEFAULT_MEMORY_LIMIT;
				}
				i.memoryLimitation = memoryLimitMB * (1 << 20);
			}
			i.planRoadDirection = parseSilentInt(getAttribute(i.router, "planRoadDirection"), i.planRoadDirection);
			
			return i;
		}
		
		private String getAttribute(VehicleRouter router, String propertyName) {
			if (router.containsAttribute(propertyName)) {
				return router.getAttribute(propertyName);
			}
			return attributes.get(propertyName);
		}
		
	}

	private static int parseSilentInt(String t, int v) {
		if (t == null || t.length() == 0) {
			return v;
		}
		return Integer.parseInt(t);
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

	
	private static RoutingConfiguration.Builder DEFAULT;

	public static RoutingConfiguration.Builder getDefault() {
		if (DEFAULT == null) {
			try {
				DEFAULT = parseFromInputStream(RoutingConfiguration.class.getResourceAsStream("routing.xml"));
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}
		return DEFAULT;
	}

	public static RoutingConfiguration.Builder parseFromInputStream(InputStream is) throws IOException, XmlPullParserException {
		XmlPullParser parser = PlatformUtil.newXMLPullParser();
		final RoutingConfiguration.Builder config = new RoutingConfiguration.Builder();
		NewGeneralRouter currentRouter = null;
		RouteDataObjectAttribute currentAttribute = null;
		Stack<RoutingRule> rulesStck = new Stack<RoutingConfiguration.RoutingRule>();
		parser.setInput(is, "UTF-8");
		int tok;
		while ((tok = parser.next()) != XmlPullParser.END_DOCUMENT) {
			if (tok == XmlPullParser.START_TAG) {
				String name = parser.getName();
				if ("osmand_routing_config".equals(name)) {
					config.defaultRouter = parser.getAttributeValue("", "defaultProfile");
				} else if ("routingProfile".equals(name)) {
					currentRouter = parseRoutingProfile(parser, config);
				} else if ("attribute".equals(name)) {
					parseAttribute(parser, config, currentRouter);
				} else if ("parameter".equals(name)) {
					parseRoutingParameter(parser, currentRouter);
				} else if ("point".equals(name) || "way".equals(name)) {
					String attribute = parser.getAttributeValue("", "attribute");
					currentAttribute = RouteDataObjectAttribute.getValueOf(attribute);
				} else {
					parseRoutingRule(parser, currentRouter, currentAttribute, rulesStck);
				}
			} else if (tok == XmlPullParser.END_TAG) {
				String pname = parser.getName();
				if ("select".equals(pname) || "if".equals(pname) || "ifnot".equals(pname)) {
					rulesStck.pop();
				}
			}
		}
		return config;
	}

	private static void parseRoutingParameter(XmlPullParser parser, NewGeneralRouter currentRouter) {
		String description = parser.getAttributeValue("", "description");
		String name = parser.getAttributeValue("", "name");
		String id = parser.getAttributeValue("", "id");
		String type = parser.getAttributeValue("", "type");
		if(type.equalsIgnoreCase("boolean")) {
			currentRouter.registerBooleanParameter(id, name, description);
		} else if(type.equalsIgnoreCase("numeric")) {
			String values = parser.getAttributeValue("", "values");
			String valueDescriptions = parser.getAttributeValue("", "valueDescriptions");
			String[] strValues = values.split(",");
			Double[] vls = new Double[strValues.length];
			for(int i =0; i< vls.length; i++) {
				vls[i] =Double.parseDouble(strValues[i].trim());
			}
			currentRouter.registerNumericParameter(id, name, description, vls , 
					valueDescriptions.split(","));
		} else {
			throw new UnsupportedOperationException("Unsupported routing parameter type - " + type);
		}
	}
	
	private static class RoutingRule {
		String tagName;
		String t;
		String v;
		String param;
	}

	private static void parseRoutingRule(XmlPullParser parser, NewGeneralRouter currentRouter, RouteDataObjectAttribute attr,
			Stack<RoutingRule> stack) {
		String pname = parser.getName();
		if ("select".equals(pname) || "if".equals(pname) || "ifnot".equals(pname)) {
			if(attr == null){
				throw new NullPointerException("Select tag filter outside road attribute < " + pname + " > : "+parser.getLineNumber());
			}
			RoutingRule rr = new RoutingRule();
			rr.tagName = pname;
			rr.t = parser.getAttributeValue("", "t");
			rr.v = parser.getAttributeValue("", "v");
			rr.param = parser.getAttributeValue("", "param");
			
			RouteAttributeContext ctx = currentRouter.getObjContext(attr);
			if("select".equals(rr.tagName)) {
				String val = parser.getAttributeValue("", "value");
				ctx.registerNewRule(val);
				addSubclause(rr, ctx);
				for (int i = 0; i < stack.size(); i++) {
					addSubclause(stack.get(i), ctx);
				}
			} else if(stack.size() > 0 && stack.peek().tagName.equals("select")) {
				addSubclause(rr, ctx);
			}
			stack.push(rr);
		}
	}

	private static void addSubclause(RoutingRule rr, RouteAttributeContext ctx) {
		boolean not = "ifnot".equals(rr.tagName);
		if(!Algorithms.isEmpty(rr.param)) {
			ctx.getLastRule().registerAndParamCondition(rr.param, not);
		}
		if (!Algorithms.isEmpty(rr.t)) {
			ctx.getLastRule().registerAndTagValueCondition(rr.t, Algorithms.isEmpty(rr.v) ? null : rr.v, not);
		}
	}

	

	private static NewGeneralRouter parseRoutingProfile(XmlPullParser parser, final RoutingConfiguration.Builder config) {
		String currentSelectedRouter = parser.getAttributeValue("", "name");
		Map<String, String> attrs = new LinkedHashMap<String, String>();
		for(int i=0; i< parser.getAttributeCount(); i++) {
			attrs.put(parser.getAttributeName(i), parser.getAttributeValue(i));
		}
		GeneralRouterProfile c = Algorithms.parseEnumValue(GeneralRouterProfile.values(), 
				parser.getAttributeValue("", "baseProfile"), GeneralRouterProfile.CAR);
		NewGeneralRouter currentRouter = new NewGeneralRouter(c, attrs);
		config.routers.put(currentSelectedRouter, currentRouter);
		return currentRouter;
	}

	private static void parseAttribute(XmlPullParser parser, final RoutingConfiguration.Builder config, VehicleRouter currentRouter) {
		if(currentRouter != null) {
			currentRouter.addAttribute(parser.getAttributeValue("", "name"), 
					parser.getAttributeValue("", "value"));
		} else {
			config.attributes.put(parser.getAttributeValue("", "name"), 
					parser.getAttributeValue("", "value"));
		}
	}
	
}
