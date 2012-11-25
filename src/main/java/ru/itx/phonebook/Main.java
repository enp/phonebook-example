/*
 * Copyright (c) 2012 Eugene Prokopiev <enp@itx.ru>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.itx.phonebook;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.directory.server.core.DefaultDirectoryService;
import org.apache.directory.server.core.entry.DefaultServerEntry;
import org.apache.directory.server.core.partition.Partition;
import org.apache.directory.server.core.partition.impl.btree.jdbm.JdbmPartition;
import org.apache.directory.server.ldap.LdapServer;
import org.apache.directory.server.protocol.shared.transport.TcpTransport;
import org.apache.directory.shared.ldap.entry.Entry;
import org.apache.directory.shared.ldap.ldif.LdifEntry;
import org.apache.directory.shared.ldap.ldif.LdifReader;
import org.apache.directory.shared.ldap.name.LdapDN;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;

public class Main {
	
	private LdapServer ldap;
	private DefaultDirectoryService directory;
	private Server http;
	
	public void start() throws Exception {
		
		directory = new DefaultDirectoryService();
		directory.setAccessControlEnabled(true);
        Partition partition = new JdbmPartition();
        partition.setId("home");
        partition.setSuffix("dc=home");
        directory.addPartition(partition);
        directory.startup();
        
        InputStream in = getClass().getClassLoader().getResourceAsStream("home.ldif");
        for (LdifEntry ldifEntry : new LdifReader (in)) {
        	directory.getAdminSession().add(new DefaultServerEntry(directory.getRegistries(), ldifEntry.getEntry()));
        }
        
		http = new Server(8080);
		http.setHandler(new AbstractHandler() {
			private Map<String,String> params(HttpServletRequest request) throws IOException {
				BufferedReader reader = request.getReader();
			    StringBuilder sb = new StringBuilder();
			    String line = reader.readLine();
			    while (line != null) {
			        sb.append(line + "\n");
			        line = reader.readLine();
			    }
			    reader.close();
			    String data = sb.toString();
			    String[] items = data.split("&");
			    Map<String, String> params = new HashMap<String,String>();
			    for (String item : items) {
			    	String[] param = item.split("=");
			    	params.put(param[0], URLDecoder.decode(param[1], "UTF-8"));
			    }
			    return params;
			}
			public void handle(String AbstractHandler, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
					throws IOException, ServletException {
				Map<String,String> params = params(request);
				String login = params.get("user");
				String begin = "<document type=\"freeswitch/xml\"><section name=\"directory\"><domain name=\"$${local_address}\"><params><param name=\"dial-string\" value=\"${sofia_contact(${dialed_user}@${dialed_domain})}\"/></params><groups><group name=\"local\"><users>";
				String content = "";
				String end = "</users></group></groups></domain></section></document>";
				try {
					Entry entry = directory.getAdminSession().lookup(new LdapDN("cn="+login+",dc=home"));
					System.out.println("Entry : "+entry.toString());
					String password = new String(entry.get("userPassword").getBytes());
					String number = entry.get("telephoneNumber").getString();
					content = "<user id=\""+login+"\" number-alias=\""+number+"\"><params><param name=\"password\" value=\""+password+"\"/></params></user>";
				} catch (Exception e) {
					content = "<user/>";
				}
				response.setContentType("text/xml;charset=utf-8");
		        response.setStatus(HttpServletResponse.SC_OK);
		        response.getWriter().write(begin+content+end);
		        baseRequest.setHandled(true);
			}
		});
		http.start();
		
        ldap = new LdapServer();
        ldap.setDirectoryService(directory);
        ldap.setTransports(new TcpTransport(10389));        
        ldap.start();
	}

	public void stop() throws Exception {
		http.stop();
		ldap.stop();
	}

	public Main() {
		
	}

	public static void main(String[] args) throws Exception {
		final Main app = new Main();
		app.start();
		Runtime.getRuntime().addShutdownHook(new Thread() {
		    public void run() { 
		    	try { app.stop(); } catch (Exception e) { e.printStackTrace(); }
		    }
		});
	}

}
