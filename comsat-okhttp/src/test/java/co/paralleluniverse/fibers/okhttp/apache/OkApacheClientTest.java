/*
 * COMSAT
 * Copyright (c) 2013-2015, Parallel Universe Software Co. All rights reserved.
 *
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *
 *   or (per the licensee's choosing)
 *
 * under the terms of the GNU Lesser General Public License version 3.0
 * as published by the Free Software Foundation.
 */
/*
 * Based on the corresponding class in okhttp-apache.
 * Copyright 2015 Square, Inc.
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package co.paralleluniverse.fibers.okhttp.apache;

import co.paralleluniverse.fibers.Fiber;
import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.fibers.okhttp.FiberOkHttpClient;
import co.paralleluniverse.fibers.okhttp.FiberOkHttpUtils;
import com.squareup.okhttp.apache.OkApacheClient;
import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.MockWebServer;
import com.squareup.okhttp.mockwebserver.RecordedRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import okio.Buffer;
import okio.GzipSink;
import okio.Okio;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static com.squareup.okhttp.internal.Util.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OkApacheClientTest {
  private MockWebServer server;
  private OkApacheClient client;

  @Before public void setUp() throws IOException {
    client = new OkApacheClient(new FiberOkHttpClient());
    server = new MockWebServer();
    server.start();
  }

  @After public void tearDown() throws IOException {
    server.shutdown();
  }

  @Test public void success() throws Exception {
    server.enqueue(new MockResponse().setBody("Hello, World!"));

    HttpGet request = new HttpGet(server.getUrl("/").toURI());
    HttpResponse response = FiberOkHttpUtils.execute(client, request);
    String actual = EntityUtils.toString(response.getEntity());
    assertEquals("Hello, World!", actual);
  }

  @Test public void redirect() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", "/foo"));
    server.enqueue(new MockResponse().setBody("Hello, Redirect!"));

    HttpGet request = new HttpGet(server.getUrl("/").toURI());
    HttpResponse response = FiberOkHttpUtils.execute(client, request);
    String actual = EntityUtils.toString(response.getEntity(), UTF_8);
    assertEquals("Hello, Redirect!", actual);
  }

  @Test public void sessionExpired() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(422));

    HttpGet request = new HttpGet(server.getUrl("/").toURI());
    HttpResponse response = FiberOkHttpUtils.execute(client, request);
    assertEquals(422, response.getStatusLine().getStatusCode());
  }

  @Test public void headers() throws Exception {
    server.enqueue(new MockResponse().addHeader("Foo", "Bar"));
    server.enqueue(new MockResponse().addHeader("Foo", "Bar").addHeader("Foo", "Baz"));

    HttpGet request1 = new HttpGet(server.getUrl("/").toURI());
    HttpResponse response1 = FiberOkHttpUtils.execute(client, request1);
    Header[] headers1 = response1.getHeaders("Foo");
    assertEquals(1, headers1.length);
    assertEquals("Bar", headers1[0].getValue());

    HttpGet request2 = new HttpGet(server.getUrl("/").toURI());
    HttpResponse response2 = FiberOkHttpUtils.execute(client, request2);
    Header[] headers2 = response2.getHeaders("Foo");
    assertEquals(2, headers2.length);
    assertEquals("Bar", headers2[0].getValue());
    assertEquals("Baz", headers2[1].getValue());
  }

  @Test public void postByteEntity() throws Exception {
    server.enqueue(new MockResponse());

    final HttpPost post = new HttpPost(server.getUrl("/").toURI());
    byte[] body = "Hello, world!".getBytes(UTF_8);
    post.setEntity(new ByteArrayEntity(body));
    FiberOkHttpUtils.execute(client, post);

    RecordedRequest request = server.takeRequest();
    assertEquals("Hello, world!", request.getBody().readUtf8());
    assertEquals(request.getHeader("Content-Length"), "13");
  }

  @Test public void postInputStreamEntity() throws Exception {
    server.enqueue(new MockResponse());

    final HttpPost post = new HttpPost(server.getUrl("/").toURI());
    byte[] body = "Hello, world!".getBytes(UTF_8);
    post.setEntity(new InputStreamEntity(new ByteArrayInputStream(body), body.length));
    FiberOkHttpUtils.execute(client, post);

    RecordedRequest request = server.takeRequest();
    assertEquals("Hello, world!", request.getBody().readUtf8());
    assertEquals(request.getHeader("Content-Length"), "13");
  }

  @Test public void postOverrideContentType() throws Exception {
    server.enqueue(new MockResponse());

    HttpPost httpPost = new HttpPost();
    httpPost.setURI(server.getUrl("/").toURI());
    httpPost.addHeader("Content-Type", "application/xml");
    httpPost.setEntity(new StringEntity("<yo/>"));
    FiberOkHttpUtils.execute(client, httpPost);

    RecordedRequest request = server.takeRequest();
    assertEquals(request.getHeader("Content-Type"), "application/xml");
  }

  @Test public void contentType() throws Exception {
    server.enqueue(new MockResponse().setBody("<html><body><h1>Hello, World!</h1></body></html>")
        .setHeader("Content-Type", "text/html"));
    server.enqueue(new MockResponse().setBody("{\"Message\": { \"text\": \"Hello, World!\" } }")
        .setHeader("Content-Type", "application/json"));
    server.enqueue(new MockResponse().setBody("Hello, World!"));

    HttpGet request1 = new HttpGet(server.getUrl("/").toURI());
    HttpResponse response1 = FiberOkHttpUtils.execute(client, request1);
    Header[] headers1 = response1.getHeaders("Content-Type");
    assertEquals(1, headers1.length);
    assertEquals("text/html", headers1[0].getValue());
    assertNotNull(response1.getEntity().getContentType());
    assertEquals("text/html", response1.getEntity().getContentType().getValue());

    HttpGet request2 = new HttpGet(server.getUrl("/").toURI());
    HttpResponse response2 = FiberOkHttpUtils.execute(client, request2);
    Header[] headers2 = response2.getHeaders("Content-Type");
    assertEquals(1, headers2.length);
    assertEquals("application/json", headers2[0].getValue());
    assertNotNull(response2.getEntity().getContentType());
    assertEquals("application/json", response2.getEntity().getContentType().getValue());

    HttpGet request3 = new HttpGet(server.getUrl("/").toURI());
    HttpResponse response3 = FiberOkHttpUtils.execute(client, request3);
    Header[] headers3 = response3.getHeaders("Content-Type");
    assertEquals(0, headers3.length);
    assertNull(response3.getEntity().getContentType());
  }

  @Test public void contentEncoding() throws Exception {
    String text = "{\"Message\": { \"text\": \"Hello, World!\" } }";
    server.enqueue(new MockResponse().setBody(gzip(text))
        .setHeader("Content-Encoding", "gzip"));

    HttpGet request = new HttpGet(server.getUrl("/").toURI());
    request.setHeader("Accept-encoding", "gzip"); // Not transparent gzip.
    HttpResponse response = FiberOkHttpUtils.execute(client, request);
    HttpEntity entity = response.getEntity();

    Header[] encodingHeaders = response.getHeaders("Content-Encoding");
    assertEquals(1, encodingHeaders.length);
    assertEquals("gzip", encodingHeaders[0].getValue());
    assertNotNull(entity.getContentEncoding());
    assertEquals("gzip", entity.getContentEncoding().getValue());

    assertEquals(text, gunzip(entity));
  }

  @Test public void jsonGzipResponse() throws Exception {
    String text = "{\"Message\": { \"text\": \"Hello, World!\" } }";
    server.enqueue(new MockResponse().setBody(gzip(text))
        .setHeader("Content-Encoding", "gzip")
        .setHeader("Content-Type", "application/json"));

    HttpGet request1 = new HttpGet(server.getUrl("/").toURI());
    request1.setHeader("Accept-encoding", "gzip"); // Not transparent gzip.

    HttpResponse response = FiberOkHttpUtils.execute(client, request1);
    HttpEntity entity = response.getEntity();

    Header[] encodingHeaders = response.getHeaders("Content-Encoding");
    assertEquals(1, encodingHeaders.length);
    assertEquals("gzip", encodingHeaders[0].getValue());
    assertNotNull(entity.getContentEncoding());
    assertEquals("gzip", entity.getContentEncoding().getValue());

    Header[] typeHeaders = response.getHeaders("Content-Type");
    assertEquals(1, typeHeaders.length);
    assertEquals("application/json", typeHeaders[0].getValue());
    assertNotNull(entity.getContentType());
    assertEquals("application/json", entity.getContentType().getValue());

    assertEquals(text, gunzip(entity));
  }

  @Test public void jsonTransparentGzipResponse() throws Exception {
    String text = "{\"Message\": { \"text\": \"Hello, World!\" } }";
    server.enqueue(new MockResponse().setBody(gzip(text))
        .setHeader("Content-Encoding", "gzip")
        .setHeader("Content-Type", "application/json"));

    HttpGet request = new HttpGet(server.getUrl("/").toURI());
    HttpResponse response = FiberOkHttpUtils.execute(client, request);
    HttpEntity entity = response.getEntity();

    // Expecting transparent gzip response by not adding header "Accept-encoding: gzip"
    Header[] encodingHeaders = response.getHeaders("Content-Encoding");
    assertEquals(0, encodingHeaders.length);
    assertNull(entity.getContentEncoding());

    // Content length should be absent.
    Header[] lengthHeaders = response.getHeaders("Content-Length");
    assertEquals(0, lengthHeaders.length);
    assertEquals(-1, entity.getContentLength());

    Header[] typeHeaders = response.getHeaders("Content-Type");
    assertEquals(1, typeHeaders.length);
    assertEquals("application/json", typeHeaders[0].getValue());
    assertNotNull(entity.getContentType());
    assertEquals("application/json", entity.getContentType().getValue());

    assertEquals(text, EntityUtils.toString(entity, UTF_8));
  }

  private static Buffer gzip(String body) throws IOException {
    Buffer buffer = new Buffer();
    Okio.buffer(new GzipSink(buffer)).writeUtf8(body).close();
    return buffer;
  }

  private static String gunzip(HttpEntity body) throws IOException {
    InputStream in = new GZIPInputStream(body.getContent());
    Buffer buffer = new Buffer();
    byte[] temp = new byte[1024];
    int read;
    while ((read = in.read(temp)) != -1) {
      buffer.write(temp, 0, read);
    }
    return buffer.readUtf8();
  }
}
