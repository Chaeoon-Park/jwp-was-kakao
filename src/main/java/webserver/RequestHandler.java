package webserver;

import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Helper;
import com.github.jknack.handlebars.Template;
import com.github.jknack.handlebars.io.ClassPathTemplateLoader;
import com.github.jknack.handlebars.io.TemplateLoader;
import com.google.common.collect.Sets;
import db.DataBase;
import model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import utils.FileIoUtils;

import java.io.*;
import java.net.Socket;
import java.net.URISyntaxException;
import java.util.*;

import static java.nio.charset.StandardCharsets.UTF_8;

public class RequestHandler implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(RequestHandler.class);
    private static final String TEMPLATE_PATH = "./templates";
    private static final String STATIC_PATH = "./static";

    private final Socket connection;

    public RequestHandler(Socket connectionSocket) {
        this.connection = connectionSocket;
    }

    public void run() {
        logger.debug("New Client Connect! Connected IP : {}, Port : {}", connection.getInetAddress(), connection.getPort());

        try (InputStream in = connection.getInputStream(); OutputStream out = connection.getOutputStream()) {
            HttpRequest httpRequest = parseRequest(in);
            String requestURI = httpRequest.getRequestURI();
            logger.debug("requestURI: {}", requestURI);

            DataOutputStream dos = new DataOutputStream(out);

            if (requestURI.equals("/user/create") && httpRequest.getMethod().equals("POST")) {
                Response response = handleUserCreate(httpRequest.getUser());
                response(dos, response);
                return;
            }

            if (requestURI.equals("/user/login") && httpRequest.getMethod().equals("POST")) {
                Response response = handleLogin(httpRequest.getEntity());
                response(dos, response);
                return;
            }

            if (requestURI.equals("/user/list") && httpRequest.getMethod().equals("GET")) {
                Response response = handleList(httpRequest);
                response(dos, response);
                return;
            }

            responseStaticContent(requestURI, dos);
        } catch (IOException | URISyntaxException e) {
            logger.error(e.getMessage());
        }
    }

    private void response(DataOutputStream dos, Response response) throws IOException {
        if (StringUtils.hasText(response.getLocation())) {
            response302Header(dos, response.getLocation(), response.getHeaders());
        }

        if (StringUtils.hasText(response.getViewName())) {
            TemplateLoader loader = new ClassPathTemplateLoader();
            loader.setPrefix("/templates");
            loader.setSuffix(".html");
            Handlebars handlebars = new Handlebars(loader);
            handlebars.registerHelper("inc", (Helper<Integer>) (context, options) -> context + 1);

            Template template = handlebars.compile(response.getViewName());
            byte[] body = template.apply(response.getModel()).getBytes(UTF_8);
            response200Header(dos, body.length);
            responseBody(dos, body);
        }
    }

    private Response handleList(HttpRequest httpRequest) {
        if (httpRequest.getCookies().contains("logined=true")) {
            Response response = new Response();
            response.setLocation("/user/login.html");
            return response;
        }

        Response response = new Response();
        response.setModel(DataBase.findAll());
        response.setViewName("user/list");
        return response;
    }

    private Response handleLogin(Map<String, String> entity) {
        User user = DataBase.findUserById(entity.get("userId"));
        if (user.getPassword().equals(entity.get("password"))) {
            Response response = new Response();
            response.setLocation("/index.html");
            response.setHeaders("Set-Cookie: logined=true; Path=/");
            return response;
        }

        Response response = new Response();
        response.setLocation("/user/login_failed.html");
        response.setHeaders("Set-Cookie: logined=false; Path=/");
        return response;
    }

    private Response handleUserCreate(User user) {
        DataBase.addUser(user);
        Response response = new Response();
        response.setLocation("/index.html");
        return response;
    }

    private void responseStaticContent(String requestURI, DataOutputStream dos) throws IOException, URISyntaxException {
        byte[] body = FileIoUtils.loadFileFromClasspath(getBasePath(requestURI) + requestURI);
        response200Header(dos, body.length, getContentType(requestURI));
        responseBody(dos, body);
    }

    private String getContentType(String requestURI) {
        switch (requestURI.substring(requestURI.lastIndexOf(".") + 1)) {
            case "js":
                return "application/js";
            case "css":
                return "text/css";
            default:
                return "text/html;charset=utf-8";
        }
    }

    private String getBasePath(String requestURI) {
        return Sets.newHashSet("/css", "/js", "/fonts", "/images") //
                .stream() //
                .filter(requestURI::startsWith) //
                .findAny() //
                .map(path -> STATIC_PATH) //
                .orElse(TEMPLATE_PATH);
    }

    private HttpRequest parseRequest(InputStream in) throws IOException {
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(in, UTF_8));
        RequestParser requestParser = new RequestParser(bufferedReader);
        return requestParser.parse();
    }

    private void response200Header(DataOutputStream dos, int length) {
        response200Header(dos, length, "text/html;charset=utf-8");
    }

    private void response200Header(DataOutputStream dos, int lengthOfBodyContent, String contentType) {
        try {
            dos.writeBytes("HTTP/1.1 200 OK \r\n");
            dos.writeBytes("Content-Type: " + contentType + "\r\n");
            dos.writeBytes("Content-Length: " + lengthOfBodyContent + "\r\n");
            dos.writeBytes("\r\n");
        } catch (IOException e) {
            logger.error(e.getMessage());
        }
    }


    private void response302Header(DataOutputStream dos, String location, List<String> headers) {
        try {
            dos.writeBytes("HTTP/1.1 302 Found \r\n");
            dos.writeBytes("Location: " + location + "\r\n");
            for (String header : headers) {
                dos.writeBytes(header + "\r\n");
            }
            dos.writeBytes("\r\n");
        } catch (IOException e) {
            logger.error(e.getMessage());
        }
    }

    private void responseBody(DataOutputStream dos, byte[] body) {
        try {
            dos.write(body, 0, body.length);
            dos.flush();
        } catch (IOException e) {
            logger.error(e.getMessage());
        }
    }

    private static class Response {
        private String location;
        private final List<String> headers = new ArrayList<>();
        private Object model;
        private String viewName;

        public void setLocation(String location) {
            this.location = location;
        }

        public String getLocation() {
            return location;
        }

        public void setHeaders(String headers) {
            this.headers.add(headers);
        }

        public List<String> getHeaders() {
            return Collections.unmodifiableList(headers);
        }

        public void setModel(Object model) {
            this.model = model;
        }

        public Object getModel() {
            return model;
        }

        public void setViewName(String viewName) {
            this.viewName = viewName;
        }

        public String getViewName() {
            return viewName;
        }
    }
}
