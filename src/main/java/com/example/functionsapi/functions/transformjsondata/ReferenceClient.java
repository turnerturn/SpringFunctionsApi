package com.example.functionsapi.functions.transformjsondata;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.example.functionsapi.common.json.JsonChecks;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.XMLConstants;
import org.w3c.dom.*;
import org.xml.sax.*;
import org.slf4j.*;

/** Bounded GET-only reference client with verified HTTPS, no redirects, and hardened XML parsing. */
class ReferenceClient {
    private static final Logger log=LoggerFactory.getLogger(ReferenceClient.class);
    // DNS may ignore interruption; a fixed worker cap prevents stalled lookups growing without bound.
    private static final ThreadPoolExecutor workers=new ThreadPoolExecutor(16,16,0,TimeUnit.MILLISECONDS,
        new SynchronousQueue<>(),task -> { Thread thread=new Thread(task,"reference-get"); thread.setDaemon(true); return thread; },
        new ThreadPoolExecutor.AbortPolicy());
    private final ObjectMapper mapper;
    private final Set<String> allowedOrigins;
    /** Initializes owned dependencies and bounded resources. */
    ReferenceClient(ObjectMapper mapper,String origins) {
        this.mapper=mapper;
        Set<String> values=new HashSet<>();
        for (String value : origins.split(",")) if (!value.isBlank()) {
            URI uri=URI.create(value.trim());
            if (uri.getRawQuery()!=null || !(uri.getPath()==null || uri.getPath().isEmpty() || uri.getPath().equals("/")))
                throw new IllegalArgumentException();
            values.add(origin(uri));
        }
        allowedOrigins=Set.copyOf(values);
    }
    /** Resolves path parameters from original input data and checks destinations before any fetch. */
    URI resolve(JsonNode step,JsonNode data) {
        URI base=URI.create(step.path("baseUrl").asText());
        String origin=origin(base);
        if (!allowedOrigins.contains(origin) || base.getRawQuery()!=null || !(base.getPath()==null || base.getPath().isEmpty() || base.getPath().equals("/")))
            throw new IllegalArgumentException();
        String path=step.path("path").asText();
        JsonNode parameters=step.get("parameters");
        if (parameters!=null) {
            var fields=parameters.fields();
            while (fields.hasNext()) {
                var e=fields.next(); JsonNode value=JsonMapping.get(data,e.getValue().textValue());
                if (!value.isValueNode() || value.isNull()) throw new IllegalArgumentException();
                path=path.replace("{"+e.getKey()+"}",URLEncoder.encode(value.asText(),StandardCharsets.UTF_8).replace("+","%20"));
            }
        }
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("{") || path.contains("}") || path.contains("?") || path.contains("#"))
            throw new IllegalArgumentException();
        StringJoiner query=new StringJoiner("&");
        JsonNode params=step.get("query");
        if (params!=null) {
            if (!params.isObject() || params.size()>16) throw new IllegalArgumentException();
            var fields=params.fields();
            while (fields.hasNext()) {
                var entry=fields.next(); JsonNode value=entry.getValue();
                if (!entry.getKey().matches("[A-Za-z0-9_-]{1,64}") || value==null || value.isNull()
                    || !value.isValueNode() || value.asText().length()>1024) throw new IllegalArgumentException();
                query.add(URLEncoder.encode(entry.getKey(),StandardCharsets.UTF_8)+"="+
                    URLEncoder.encode(value.asText(),StandardCharsets.UTF_8));
            }
        }
        URI resolved=URI.create(origin+path+(query.length()==0?"":"?"+query));
        if (!origin(resolved).equals(origin)) throw new IllegalArgumentException(); return resolved;
    }
    /** Performs one bounded fetch and parses JSON or XML without exposing response bodies in failures. */
    JsonNode fetch(URI uri,String format,int timeoutMs,long totalDeadline) {
        long end=Math.min(totalDeadline,System.nanoTime()+timeoutMs*1_000_000L);
        Future<JsonNode> pending;
        try { pending=workers.submit(() -> fetchBlocking(uri,format,end)); }
        catch (RejectedExecutionException e) { throw new JsonMapping.MappingFailure("BUSY"); }
        try { return pending.get(remaining(end),TimeUnit.MILLISECONDS); }
        catch (TimeoutException e) { throw new JsonMapping.MappingFailure("REFERENCE_TIMEOUT"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new JsonMapping.MappingFailure("INTERRUPTED"); }
        catch (ExecutionException e) {
            if (e.getCause() instanceof JsonMapping.MappingFailure failure) throw failure;
            throw new JsonMapping.MappingFailure("REFERENCE_NETWORK_ERROR");
        } finally { pending.cancel(true); }
    }
    /** Owns connection cleanup on a capped worker, including when a caller's deadline expires during DNS. */
    JsonNode fetchBlocking(URI uri,String format,long end) {
        HttpURLConnection connection=null;
        try {
            connection=(HttpURLConnection)uri.toURL().openConnection(Proxy.NO_PROXY);
            connection.setInstanceFollowRedirects(false); connection.setRequestMethod("GET");
            connection.setConnectTimeout(remaining(end)); connection.setReadTimeout(remaining(end));
            connection.setRequestProperty("Accept",format.equals("xml")?"application/xml":"application/json");
            connection.connect(); connection.setReadTimeout(remaining(end));
            int status=connection.getResponseCode();
            log.debug("Reference GET finished httpStatus={} format={}",status,format);
            if (status<200 || status>=300) throw new JsonMapping.MappingFailure("REFERENCE_HTTP_ERROR");
            ByteArrayOutputStream body=new ByteArrayOutputStream();
            try (InputStream input=connection.getInputStream()) {
                byte[] buffer=new byte[8192];
                while (true) {
                    connection.setReadTimeout(remaining(end)); int count=input.read(buffer);
                    if (count<0) break;
                    if (body.size()+count>1048576) throw new JsonMapping.MappingFailure("REFERENCE_SIZE_LIMIT");
                    body.write(buffer,0,count);
                }
            }
            JsonNode result=format.equals("xml")?xml(body.toByteArray()):mapper.readerFor(JsonNode.class)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(body.toByteArray());
            if (result==null) throw new JsonMapping.MappingFailure("REFERENCE_PARSE_ERROR");
            JsonChecks.tree(result); remaining(end);
            return result;
        } catch (JsonMapping.MappingFailure e) { throw e; }
        catch (SocketTimeoutException e) { throw new JsonMapping.MappingFailure("REFERENCE_TIMEOUT"); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new JsonMapping.MappingFailure("REFERENCE_PARSE_ERROR"); }
        catch (IOException e) { throw new JsonMapping.MappingFailure("REFERENCE_NETWORK_ERROR"); }
        catch (Exception e) { throw new JsonMapping.MappingFailure("REFERENCE_PARSE_ERROR"); }
        finally { if (connection!=null) connection.disconnect(); }
    }
    /** Returns remaining timeout, rejecting expired requests rather than using Java's infinite timeout 0. */
    private int remaining(long end) {
        if (Thread.currentThread().isInterrupted()) throw new JsonMapping.MappingFailure("INTERRUPTED");
        long left=end-System.nanoTime();
        if (left<=0) throw new JsonMapping.MappingFailure("REFERENCE_TIMEOUT");
        return (int)Math.max(1,(left+999999)/1000000);
    }
    /** Canonicalizes scheme/host/port and rejects credentials, fragments, and malformed hosts; DNS waits are isolated on capped workers. */
    private static String origin(URI uri) {
        if (!Set.of("http","https").contains(uri.getScheme()) || uri.getUserInfo()!=null || uri.getFragment()!=null)
            throw new IllegalArgumentException();
        String host=Objects.requireNonNull(uri.getHost()).toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.matches("[0-9.]+")) host=JsonChecks.host(host);
        else if (host.length()>253 || !host.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+"))
            throw new IllegalArgumentException();
        int port=uri.getPort()==-1?(uri.getScheme().equals("https")?443:80):uri.getPort();
        if (port<1 || port>65535) throw new IllegalArgumentException();
        return uri.getScheme()+"://"+host+":"+port;
    }
    /** Parses XML with every external entity/DTD mechanism disabled and no parser output to stderr. */
    private JsonNode xml(byte[] body) throws Exception {
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        factory.setAttribute("jdk.xml.maxElementDepth",64);
        factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
        var parser=factory.newDocumentBuilder();
        parser.setErrorHandler(new ErrorHandler() {
            public void warning(SAXParseException e) throws SAXException { throw e; }
            /** Suppresses parser diagnostics that could contain source text. */
            public void error(SAXParseException e) throws SAXException { throw e; }
            public void fatalError(SAXParseException e) throws SAXException { throw e; }
        });
        Element root=parser.parse(new ByteArrayInputStream(body)).getDocumentElement();
        return mapper.createObjectNode().set(root.getTagName(),element(root,0,new int[]{0}));
    }
    /** Converts XML attributes to @name, text to #text, and repeated child names to arrays. */
    private JsonNode element(Element element,int depth,int[] count) {
        if (depth>64 || ++count[0]>20000) throw new JsonMapping.MappingFailure("REFERENCE_SIZE_LIMIT");
        ObjectNode out=mapper.createObjectNode();
        for (int i=0;i<element.getAttributes().getLength();i++) {
            Node attribute=element.getAttributes().item(i); out.put("@"+attribute.getNodeName(),attribute.getNodeValue());
        }
        StringBuilder text=new StringBuilder(); boolean children=false;
        for (Node node=element.getFirstChild();node!=null;node=node.getNextSibling()) {
            if (node instanceof Element child) {
                children=true; String key=child.getTagName(); JsonNode value=element(child,depth+1,count);
                if (!out.has(key)) out.set(key,value);
                else if (out.get(key).isArray()) ((ArrayNode)out.get(key)).add(value);
                else { ArrayNode array=mapper.createArrayNode().add(out.get(key)).add(value); out.set(key,array); }
            } else if (node.getNodeType()==Node.TEXT_NODE || node.getNodeType()==Node.CDATA_SECTION_NODE) text.append(node.getNodeValue());
        }
        if (!children && out.isEmpty()) return TextNode.valueOf(text.toString());
        if (!text.toString().isBlank()) out.put("#text",text.toString()); return out;
    }
}
