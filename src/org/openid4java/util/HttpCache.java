/*
 * Copyright 2006-2008 Sxip Identity Corporation
 */

package org.openid4java.util;

import org.apache.http.client.HttpClient;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.params.AllClientPNames;
import org.apache.http.util.EntityUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.Map;
import java.util.Iterator;
import java.util.HashMap;
import java.io.IOException;

/**
 * Wrapper cache around HttpClient providing caching for HTTP requests.
 * Intended to be used to optimize the number of HTTP requests performed
 * during OpenID discovery.
 *
 * @author Marius Scurtescu, Johnny Bufu
 */
public class HttpCache
{
    private static Log _log = LogFactory.getLog(HttpCache.class);
    private static final boolean DEBUG = _log.isDebugEnabled();

    /**
     * HttpClient used to place the HTTP requests.
     */
    private HttpClient _client;


    /**
     * Default set of HTTP request options to be used when placing HTTP
     * requests, if a custom one was not specified.
     */
    private HttpRequestOptions _defaultOptions = new HttpRequestOptions();

    /**
     * Cache for GET requests. Map of URL -> HttpResponse.
     */
    private Map _getCache = new HashMap();

    // todo: cache management

    /**
     * Cache for HEAD requests. Map of URL -> HttpResponse.
     */
    private Map _headCache = new HashMap();

    /**
     * Constructs a new HttpCache object, that will be initialized with the
     * default set of HttpRequestOptions.
     *
     * @see HttpRequestOptions
     */
    public HttpCache()
    {
        _client = HttpClientFactory.getInstance(
                _defaultOptions.getMaxRedirects(),
                Boolean.TRUE,
                _defaultOptions.getSocketTimeout(),
                _defaultOptions.getConnTimeout(),
                null);
    }


    public HttpRequestOptions getDefaultRequestOptions()
    {
        return _defaultOptions;
    }

    /**
     * Gets a clone of the default HttpRequestOptions.
     * @return
     */
    public HttpRequestOptions getRequestOptions()
    {
        return new HttpRequestOptions(_defaultOptions);
    }

    public void setDefaultRequestOptions(HttpRequestOptions defaultOptions)
    {
        this._defaultOptions = defaultOptions;
    }

    /**
     * Removes a cached GET response.
     *
     * @param url   The URL for which to remove the cached response.
     */
    public void removeGet(String url)
    {
        if (_getCache.keySet().contains(url))
        {
            _log.info("Removing cached GET response for " + url);
            _getCache.remove(url);
        }
        else
            _log.info("NOT removing cached GET for " + url + " NOT FOUND.");
    }

    /**
     * GETs a HTTP URL. A cached copy will be returned if one exists.
     *
     * @param url       The HTTP URL to GET.
     * @return          A HttpResponse object containing the fetched data.
     *
     * @see HttpResponse
     */
    public HttpResponse get(String url) throws IOException
    {
        return get(url, _defaultOptions);
    }

    /**
     * GETs a HTTP URL. A cached copy will be returned if one exists and the
     * supplied options match it.
     *
     * @param url       The HTTP URL to GET.
     * @return          A HttpResponse object containing the fetched data.
     *
     * @see HttpRequestOptions, HttpResponse
     */
    public HttpResponse get(String url, HttpRequestOptions requestOptions)
        throws IOException
    {
        HttpResponse resp = (HttpResponse) _getCache.get(url);

        if (resp != null)
        {
            if (match(resp, requestOptions))
            {
                _log.info("Returning cached GET response for " + url);
                return resp;
            } else
            {
                _log.info("Removing cached GET for " + url);
                removeGet(url);
            }
        }

        HttpGet get = new HttpGet(url);
        
        org.apache.http.HttpResponse httpResponse = null;
        HttpEntity responseEntity = null;
        
        try
        {
            get.getParams().setParameter(AllClientPNames.HANDLE_REDIRECTS, Boolean.TRUE);

            HttpUtils.setRequestOptions(get, requestOptions);

            Map requestHeaders = requestOptions.getRequestHeaders();
            if (requestHeaders != null)
            {
                Iterator iter = requestHeaders.keySet().iterator();
                String headerName;
                while (iter.hasNext())
                {
                    headerName = (String) iter.next();
                    get.setHeader(headerName,
                            (String) requestHeaders.get(headerName));
                }
            }

            httpResponse = _client.execute(get);
            responseEntity = httpResponse.getEntity();
            int statusCode = httpResponse.getStatusLine().getStatusCode();

            String httpBody = null;
            
            boolean bodySizeExceeded = (responseEntity != null)
                && (responseEntity.getContentLength() > 0)
                && (responseEntity.getContentLength() > requestOptions.getMaxBodySize());

            if (!bodySizeExceeded)
            {
                httpBody = EntityUtils.toString(responseEntity);
            }

            resp = new HttpResponse(statusCode, httpResponse.getStatusLine().getReasonPhrase(),
                    requestOptions.getMaxRedirects(), get.getURI().toString(),
                    httpResponse.getAllHeaders(), httpBody);
            resp.setBodySizeExceeded(bodySizeExceeded);

            // save result in cache
            _getCache.put(url, resp);
        }
        finally
        {
            HttpUtils.dispose(responseEntity);
        }

        return resp;
    }

    private boolean match(HttpResponse resp, HttpRequestOptions requestOptions)
    {
        // use cache?
        if ( resp != null && ! requestOptions.isUseCache())
        {
            _log.info("Explicit fresh GET requested; removing cached copy");
            return false;
        }

        // content type rules
        String requiredContentType = requestOptions.getContentType();
        if (resp != null && requiredContentType != null)
        {
            Header responseContentType = resp.getResponseHeader("content-type");
            if ( responseContentType != null &&
                 responseContentType.getValue() != null &&
                 !responseContentType.getValue().split(";")[0]
                     .equalsIgnoreCase(requiredContentType) )
            {
                _log.info("Cached GET response does not match " +
                    "the required content type, removing.");
                return false;
            }
        }

        if (resp != null &&
            resp.getMaxRedirectsFollowed() > requestOptions.getMaxRedirects())
        {
            _log.info("Cached GET response used " +
                      resp.getMaxRedirectsFollowed() +
                      " max redirects; current requirement is: " +
                      requestOptions.getMaxRedirects());
            return false;
        }

        return true;
    }

    public HttpResponse head(String url) throws IOException
    {
        return head(url, _defaultOptions);
    }

    public HttpResponse head(String url, HttpRequestOptions requestOptions)
            throws IOException
    {
        HttpResponse resp = (HttpResponse) _headCache.get(url);

        if (resp != null)
        {
            if (match(resp, requestOptions))
            {
                _log.info("Returning cached HEAD response for " + url);
                return resp;
            } else
            {
                _log.info("Removing cached HEAD for " + url);
                removeGet(url);
            }
        }

        HttpHead head = new HttpHead(url);
        
        org.apache.http.HttpResponse httpResponse = null;
        HttpEntity responseEntity = null;
        
        try
        {
            head.getParams().setParameter(AllClientPNames.HANDLE_REDIRECTS, Boolean.TRUE);

            HttpUtils.setRequestOptions(head, requestOptions);

            httpResponse = _client.execute(head);
            responseEntity = httpResponse.getEntity();
            
            int statusCode = httpResponse.getStatusLine().getStatusCode();

            resp = new HttpResponse(statusCode, httpResponse.getStatusLine().getReasonPhrase(),
                    requestOptions.getMaxRedirects(), head.getURI().toString(),
                    httpResponse.getAllHeaders(), null);

            // save result in cache
            _headCache.put(url, resp);
        }
        finally
        {
            HttpUtils.dispose(responseEntity);
        }

        return resp;
    }

}
