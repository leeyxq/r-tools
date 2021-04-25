package top.rizon.rtools.restemplate;

import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 使用curl风格打印请求参数
 *
 * @author rizon
 * @date 2021/4/25
 */
@Slf4j
public class PrintCurlClientHttpRequestInterceptor implements ClientHttpRequestInterceptor {
    private String separator = " ";
    private boolean debugLog = false;
    private List<String> ignoreHeaders = new ArrayList<>(Arrays.asList(
            HttpHeaders.ACCEPT, HttpHeaders.CONTENT_LENGTH
    ));


    public static PrintCurlInterceptorBuilder builder() {
        return new PrintCurlInterceptorBuilder();
    }

    public static class PrintCurlInterceptorBuilder {
        private final PrintCurlClientHttpRequestInterceptor interceptor = new PrintCurlClientHttpRequestInterceptor();

        public PrintCurlInterceptorBuilder setMultiLine() {
            interceptor.separator = " \\\n";
            return this;
        }

        /**
         * is default
         */
        public PrintCurlInterceptorBuilder setSingleLine() {
            interceptor.separator = " ";
            return this;
        }

        public PrintCurlInterceptorBuilder setDebugLog() {
            interceptor.debugLog = true;
            return this;
        }

        /**
         * is default
         */
        public PrintCurlInterceptorBuilder setInfoLog() {
            interceptor.debugLog = false;
            return this;
        }

        /**
         * 忽略header
         */
        public PrintCurlInterceptorBuilder appendIgnoreHeaders(@NonNull List<String> headerKeys) {
            interceptor.ignoreHeaders.addAll(headerKeys);
            return this;
        }

        /**
         * 忽略header
         */
        public PrintCurlInterceptorBuilder setIgnoreHeaders(@NonNull List<String> headerKeys) {
            interceptor.ignoreHeaders = headerKeys;
            return this;
        }

        public PrintCurlClientHttpRequestInterceptor build() {
            return interceptor;
        }

    }


    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        try {
            printCurl(request, body);
        } catch (Exception ex) {
            log.warn("print curl error", ex);
        }
        return execution.execute(request, body);

    }

    public void printCurl(HttpRequest request, byte[] body) {
        List<CObj> cObjects = new ArrayList<>();

        //1 method
        cObjects.add(new CMethod(request.getMethodValue()));
        //2 url
        cObjects.add(new CUrl(request.getURI()));

        //3 headers
        request.getHeaders().forEach((key, vals) -> {
            if (ignoreHeaders.contains(key)) {
                return;
            }
            cObjects.add(new CHead(key, vals));
        });

        //4 body
        cObjects.add(new CBody(separator, request.getHeaders().getContentType(), body));
        cObjects.removeIf(Objects::isNull);

        String logMsg = StringUtils.join(cObjects, separator);
        if (debugLog) {
            log.debug("Request-Curl:\n{}", logMsg);
        } else {
            log.info("Request-Curl:\n{}", logMsg);
        }

    }

    public interface CObj {
    }

    @AllArgsConstructor
    public static class CMethod implements CObj {
        private final String method;

        @Override
        public String toString() {
            return "curl -X " + method;
        }
    }

    @AllArgsConstructor
    public static class CUrl implements CObj {
        private final URI uri;

        @Override
        public String toString() {
            return "'" + uri.toString() + "'";
        }
    }

    @AllArgsConstructor
    public static class CHead implements CObj {
        private final String key;
        private final List<String> values;

        @Override
        public String toString() {
            return String.format("-H '%s: %s'", key, StringUtils.join(values, ";"));
        }
    }

    @AllArgsConstructor
    public static class CBody implements CObj {
        private final String separator;
        private final MediaType mediaType;
        private final byte[] body;

        @Override
        public String toString() {
            if (body.length == 0) {
                return null;
            }
            if (MediaType.MULTIPART_FORM_DATA.isCompatibleWith(mediaType)) {
                return parseForm(separator, "-F", body);
            }
            if (MediaType.APPLICATION_FORM_URLENCODED.isCompatibleWith(mediaType)) {
                return parseUrlEncode(separator, "--data-urlencode", body);
            }

            //默认类型
            return "-d '" + new String(body) + "'";
        }

        /**
         * eg. p1=v1&p2=v2.1&p2=v2.2
         */
        @SneakyThrows
        private String parseUrlEncode(String separator, String param, byte[] body) {
            List<String> results = new ArrayList<>();

            String[] kvList = StringUtils.split(new String(body), "&");
            for (String kv : kvList) {
                String[] kvSplit = kv.split("=", 2);
                results.add(String.format("%s '%s=\"%s\"'", param, kvSplit[0], kvSplit.length > 1 ? kvSplit[1] : ""));
            }
            return StringUtils.join(results, separator);
        }

        @SneakyThrows
        private String parseForm(String separator, String param, byte[] body) {
            List<String> results = new ArrayList<>();

            BufferedReader br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
            String line, line2, key, value;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("Content-Disposition")) {
                    key = line.split("name=", 2)[1];
                    //删除引号
                    key = key.substring(1, key.length() - 1);
                    value = null;
                    //跳过一行
                    while ((line2 = br.readLine()) != null) {
                        if (line2.length() > 0) {
                            continue;
                        } else {
                            //空白行,继续读下一行
                            value = br.readLine();
                            break;
                        }
                    }
                    results.add(String.format("%s '%s=\"%s\"'", param, key, value));
                }
            }
            return StringUtils.join(results, separator);
        }
    }

}