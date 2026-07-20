package com.wanderingjew.gedcomanalyzer;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * Renders ancestor map points as a Leaflet/OpenStreetMap view — either a full
 * standalone HTML page or an embeddable section (for the analyzer report).
 * Markers are teardrop pins coloured by generation across a red-to-violet rainbow.
 */
public class AncestorMapWriter {

    private final ObjectMapper mapper = new ObjectMapper();

    /** Lines to place in the &lt;head&gt; of any page that embeds a map section. */
    public static String leafletHead() {
        return "    <link rel=\"stylesheet\" href=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.css\"/>\n"
             + "    <script src=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.js\"></script>";
    }

    /** Write a full standalone map page with the given title. */
    public void write(List<GeniAncestorFetcher.MapPoint> points, String outputPath, String title)
            throws IOException {
        String pageTitle = (title == null || title.trim().isEmpty()) ? "Ancestor Map" : title.trim();
        try (Writer w = Files.newBufferedWriter(Paths.get(outputPath), StandardCharsets.UTF_8);
             PrintWriter out = new PrintWriter(w)) {
            out.println("<!DOCTYPE html>");
            out.println("<html lang=\"en\">");
            out.println("<head>");
            out.println("  <meta charset=\"UTF-8\">");
            out.println("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
            out.println("  <title>" + escapeHtml(pageTitle) + "</title>");
            out.println(leafletHead());
            out.println("  <style>");
            out.println("    html, body { margin: 0; height: 100%; font-family: Arial, sans-serif; }");
            out.println("    #ancestor-map { height: 100%; }");
            out.println("    .map-title { position: absolute; top: 10px; left: 50%; transform: translateX(-50%);");
            out.println("                 z-index: 1000; background: rgba(255,255,255,0.92); padding: 6px 16px;");
            out.println("                 border-radius: 6px; box-shadow: 0 1px 5px rgba(0,0,0,0.3);");
            out.println("                 font-size: 16px; font-weight: bold; color: #2c3e50; }");
            out.println(legendCss());
            out.println("  </style>");
            out.println("</head>");
            out.println("<body>");
            out.println("  <div class=\"map-title\">" + escapeHtml(pageTitle) + "</div>");
            out.print(mapSection(points, "ancestor-map", "100%"));
            out.println("</body>");
            out.println("</html>");
        }
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * Return an embeddable HTML fragment (a sized div plus a self-contained script)
     * that renders the map into an element with the given id. The caller must include
     * {@link #leafletHead()} in the page head. Returns an empty string if there are
     * no points, so callers can simply skip an empty map.
     */
    public String mapSection(List<GeniAncestorFetcher.MapPoint> points, String divId, String heightCss)
            throws IOException {
        if (points == null || points.isEmpty()) {
            return "";
        }
        String json = mapper.writeValueAsString(points).replace("</", "<\\/");
        StringBuilder sb = new StringBuilder();
        sb.append("<div id=\"").append(divId).append("\" style=\"height:").append(heightCss)
          .append(";\"></div>\n");
        sb.append("<style>\n").append(legendCss()).append("\n</style>\n");
        sb.append("<script>\n");
        sb.append("(function(){\n");
        sb.append("const points = ").append(json).append(";\n");
        sb.append(script(divId));
        sb.append("\n})();\n");
        sb.append("</script>\n");
        return sb.toString();
    }

    private static String legendCss() {
        return String.join("\n",
            "    .legend { background: rgba(255,255,255,0.9); padding: 8px 10px; border-radius: 6px;",
            "              line-height: 1.5em; font-size: 12px; box-shadow: 0 1px 5px rgba(0,0,0,0.3); }",
            "    .legend .row { display: flex; align-items: center; }",
            "    .legend .swatch { width: 12px; height: 12px; border-radius: 50%; margin-right: 6px;",
            "                      border: 1px solid #333; }",
            "    .leaflet-popup-content b { font-size: 13px; }",
            "    .pin { background: none; border: none; }");
    }

    // Map-building logic, parameterised by the container id (uses local vars inside an IIFE).
    private static String script(String divId) {
        return String.join("\n",
            // Spread the rainbow over the first CAP generations; everything deeper is violet.",
            "const CAP = 40;",
            "function color(g) { const c = Math.min(g, CAP); return `hsl(${(c / CAP) * 270}, 85%, 50%)`; }",
            "function esc(s) { return (s == null ? '' : String(s)).replace(/[&<>\"']/g,",
            "  c => ({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',\"'\":'&#39;'}[c])); }",
            "function pinIcon(g) {",
            "  const c = color(g);",
            "  const svg = '<svg width=\"24\" height=\"36\" viewBox=\"0 0 24 36\" xmlns=\"http://www.w3.org/2000/svg\">' +",
            "    '<path d=\"M12 0C5.4 0 0 5.4 0 12c0 9 12 24 12 24s12-15 12-24C24 5.4 18.6 0 12 0z\" fill=\"' + c +",
            "    '\" stroke=\"#333\" stroke-width=\"1\"/><circle cx=\"12\" cy=\"12\" r=\"4.5\" fill=\"rgba(255,255,255,0.85)\"/></svg>';",
            "  return L.divIcon({ html: svg, className: 'pin', iconSize: [24, 36], iconAnchor: [12, 36], popupAnchor: [0, -32] });",
            "}",
            "const map = L.map('" + divId + "');",
            "L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {",
            "  maxZoom: 19, attribution: '&copy; OpenStreetMap contributors'",
            "}).addTo(map);",
            "const seen = {};",
            "const bounds = [];",
            "points.forEach(p => {",
            "  let lat = p.lat, lng = p.lng;",
            "  const key = lat.toFixed(4) + ',' + lng.toFixed(4);",
            "  const n = seen[key] || 0; seen[key] = n + 1;",
            "  if (n > 0) { const a = n * 2.399, r = 0.004 * Math.sqrt(n); lat += r * Math.cos(a); lng += r * Math.sin(a); }",
            "  const marker = L.marker([lat, lng], { icon: pinIcon(p.generation) }).addTo(map);",
            "  const label = p.death ? 'died' : 'born';",
            "  marker.bindPopup('<b>' + esc(p.name) + '</b><br>' + esc(p.lifeDates) +",
            "    '<br>' + label + ' at ' + esc(p.place) + '<br><i>generation ' + p.generation + '</i>');",
            "  bounds.push([lat, lng]);",
            "});",
            "if (bounds.length) { map.fitBounds(bounds, { padding: [30, 30] }); } else { map.setView([30, 10], 2); }",
            "const legend = L.control({ position: 'bottomright' });",
            "legend.onAdd = function () {",
            "  const div = L.DomUtil.create('div', 'legend');",
            "  div.innerHTML = '<b>Generation</b><br>';",
            "  [0, 5, 10, 15, 20, 25, 30, 35, 40].forEach(g => {",
            "    const label = g === 0 ? 'you' : (g >= CAP ? CAP + '+' : g);",
            "    div.innerHTML += '<div class=\"row\"><span class=\"swatch\" style=\"background:' +",
            "      color(g) + '\"></span>' + label + '</div>';",
            "  });",
            "  return div;",
            "};",
            "legend.addTo(map);"
        );
    }
}
