package com.appearnetworks.aiq;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

public abstract class AbstractAIQMojo extends AbstractMojo {

    /**
     * String literal for UTF-8 encoding.
     */
    public static final String UTF8_ENCODING = "UTF-8";

    /**
     * The name of the response document field that stores the cause of an authentication error.
     */
    private static final String ERROR_DESCRIPTION_FIELD = "error_description";

    /**
     * The name of the response document field that stores the access token of an authenticated user.
     */
    private static final String ACCESS_TOKEN_FIELD = "access_token";

    /**
     * Prefix used in requests to the integration supervisor.
     */
    private static final String URL_PREFIX = "integration/";

    /**
     * Object mapper instance used to get hold of {@link JsonFactory} object.
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Builds an URI to the integration supervisor for the given organization and action name.
     *
     * @param baseURL URL to the mobility platform supervisor, must not be null.
     * @param orgName The name of the organization to which to build the URI, must not be null.
     * @param action The name of the action to be executed by the integration supervisor, must not be null.
     * @param params List of pairs to be added to the URI as query parameters, may be null.
     * @return URI to the integration supervisor, will not be null.
     * @throws MojoExecutionException in case when provided data is invalid.
     */
    protected URI buildIntegrationURI(final URL baseURL,
                                      final String orgName,
                                      final String action,
                                      final BasicNameValuePair ... params)
            throws MojoExecutionException {
        validate("organization", orgName);
        validate("action", action);

        try {
            final StringBuilder pathBuilder = new StringBuilder(URL_PREFIX);
            pathBuilder.append(URLEncoder.encode(orgName, UTF8_ENCODING));
            pathBuilder.append('/');
            pathBuilder.append(URLEncoder.encode(action, UTF8_ENCODING));

            final URI uri = new URL(baseURL, pathBuilder.toString()).toURI();
            if (params == null) {
                return uri;
            }

            final URIBuilder uriBuilder = new URIBuilder(uri);
            for (BasicNameValuePair pair : params) {
                uriBuilder.addParameter(pair.getName(), pair.getValue());
            }

            return uriBuilder.build();
        } catch (UnsupportedEncodingException | MalformedURLException | URISyntaxException e) {
            throw new MojoExecutionException(e.getMessage());
        }
    }

    /**
     * Authenticates and authorizes given user within the platform and adds authentication header to the given request.
     *
     * @param request The request to which to add the authentication header, must not be null.
     * @param baseUrl URL of the mobility platform with which to authenticate, must not be null.
     * @param username The name of the user which to authenticate, must not be null.
     * @param password The password of the user to authenticate, must not be null.
     * @param orgName The name of the organization to which the given user belogs, must not be null.
     * @throws MojoExecutionException in case when provided data is invalid.
     * @throws MojoFailureException in case when authentication fails.
     */
    protected void addAuthenticationHeader(final HttpRequest request,
                                           final String baseUrl,
                                           final String username,
                                           final String password,
                                           final String orgName)
            throws MojoExecutionException, MojoFailureException {
        request.setHeader(
                HttpHeaders.AUTHORIZATION,
                "BEARER " + fetchAccessToken(baseUrl, username, password, orgName));
    }

    /**
     * Authenticates and authorizes given user within the platform and returns the access token associated with the
     * user session.
     *
     * @param baseUrl URL of the mobility platform with which to authenticate, must not be null.
     * @param username The name of the user which to authenticate, must not be null.
     * @param password The password of the user to authenticate, must not be null.
     * @param orgName The name of the organization to which the given user belogs, must not be null.
     * @return access token string for given user, will not be null.
     * @throws MojoExecutionException in case when provided data is invalid.
     * @throws MojoFailureException in case when authentication fails.
     */
    protected String fetchAccessToken(final String baseUrl,
                                      final String username,
                                      final String password,
                                      final String orgName)
            throws MojoExecutionException, MojoFailureException {
        validate("URL", baseUrl);
        validate("username", username);
        validate("password", password);
        validate("organization", orgName);

        final JsonFactory factory = OBJECT_MAPPER.getFactory();

        HttpUriRequest request;
        try {
            final URL url = new URL(baseUrl + "?orgName=" + URLEncoder.encode(orgName, UTF8_ENCODING));
            request = new HttpGet(url.toURI());
        } catch (UnsupportedEncodingException | URISyntaxException | MalformedURLException e) {
            throw new MojoExecutionException(e.getMessage());
        }

        final String tokenUrl = requestAndGetValue(factory, request, "links", "token");

        try {
            final URL url = new URL(tokenUrl);
            request = new HttpPost(url.toURI());
        } catch (URISyntaxException | MalformedURLException e) {
            throw new MojoExecutionException(e.getMessage());
        }

        request.setHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_FORM_URLENCODED.getMimeType());
        ((HttpPost)request).setEntity(buildBody(username, password));

        getLog().debug("Authenticating user [" + username + "] in org [" + orgName + "]");

        return requestAndGetValue(factory, request, ACCESS_TOKEN_FIELD);
    }

    /**
     * Validates given string value.
     *
     * @param name the name of the value to be used in case of failed validation, must not be null.
     * @param value value to validate.
     * @throws MojoExecutionException when validation fails.
     */
    private static void validate(final String name, final String value) throws MojoExecutionException {
        if ((value == null) || (value.trim().length() == 0)) {
            throw new MojoExecutionException("Invalid " + name);
        }
    }

    /**
     * Validates given URL value.
     *
     * @param name the name of the value to be used in case of failed validation, must not be null.
     * @param value value to validate.
     * @throws MojoExecutionException when validation fails.
     */
    private static void validate(final String name, final URL value) throws MojoExecutionException {
        if (value == null) {
            throw new MojoExecutionException("Invalid " + name);
        }
    }

    /**
     * Builds a request body using given credentials.
     *
     * @param username username to use in the request, must not be null.
     * @param password password to use in the request, must not be null.
     * @return body entity, will not be null.
     * @throws MojoExecutionException if given credentials could not be URL encoded
     */
    private static HttpEntity buildBody(final String username, final String password) throws MojoExecutionException {
        try {
            final List<NameValuePair> form = new ArrayList<>(4);
            form.add(new BasicNameValuePair("grant_type", "password"));
            form.add(new BasicNameValuePair("scope", "integration"));
            form.add(new BasicNameValuePair("username", username));
            form.add(new BasicNameValuePair("password", password));

            return new UrlEncodedFormEntity(form);
        } catch (UnsupportedEncodingException e) {
            throw new MojoExecutionException(e.getMessage());
        }
    }

    /**
     * Performs given request and retrieves value of a field identified by given path from the response.
     *
     * @param factory used to create a JSON parser, must not be null.
     * @param request request to perform, must not be null.
     * @param path path to the field to retrieve from the response, must not be null and must exist within the response
     *             document.
     * @return string value of the field, will not be null.
     * @throws MojoExecutionException in case when provided data is invalid.
     * @throws MojoFailureException in case when the request fails.
     */
    private static String requestAndGetValue(final JsonFactory factory,
                                             final HttpUriRequest request,
                                             final String... path)
        throws MojoExecutionException, MojoFailureException {
        final HttpResponse response;
        try {
            final HttpClient client = new DefaultHttpClient();
            response = client.execute(request);
        } catch (IOException e) {
            throw new MojoFailureException(e.getMessage());
        }

        final int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode != HttpStatus.SC_OK) {
            final String message;
            if (statusCode == HttpStatus.SC_BAD_REQUEST) {
                message = getValue(response, factory, ERROR_DESCRIPTION_FIELD);
            } else {
                message = response.getStatusLine().getReasonPhrase();
            }

            throw new MojoFailureException(
                    "Failed to authenticate, the status code is [" +
                    statusCode +
                    "] and error message is [" +
                    message + "]");
        }

        return getValue(response, factory, path);
    }

    /**
     * Returns value of a response field with given path.
     *
     * @param response response from which to read the field, must not be nil and must represent a valid JSON document.
     * @param factory factory used to create a JSON parser, must not be null.
     * @param path path the field for which to return the value, must not be null and must identify an existing field
     *             within the response document.
     * @return string value of the field, will not be null.
     * @throws MojoExecutionException if the field could not be read from the response.
     */
    private static String getValue(final HttpResponse response, final JsonFactory factory, final String ... path)
            throws MojoExecutionException {
        try {
            final JsonParser parser = factory.createParser(response.getEntity().getContent());

            JsonNode node = parser.readValueAsTree();
            for (final String field : path) {
                node = node.path(field);
            }

            if (node.isMissingNode()) {
                throw new MojoExecutionException("Field not found in the response");
            }

            return node.textValue();
        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage());
        }
    }

}
