/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.contrib.networkvisualization;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.xwiki.component.phase.Initializable;
import org.xwiki.component.phase.InitializationException;
import org.xwiki.contrib.websocket.WebSocket;
import org.xwiki.contrib.websocket.WebSocketHandler;
import org.xwiki.model.reference.DocumentReference;

import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;

/**
 * Abstract base class of a WebSocket network visualization service.
 *
 * @version $Id$
 */
public abstract class AbstractNetworkVisualizationService implements WebSocketHandler, Initializable
{
    protected static final String TYPE_PROPERTY = "type";
    protected static final String NODES_PROPERTY = "nodes";
    protected static final String NODE_PROPERTY = "node";

    @Inject
    protected Logger logger;

    private final Set<WebSocket> sockets = Collections.newSetFromMap(new ConcurrentHashMap<WebSocket, Boolean>());
    private final WebSocket.Callback messageCallback = new WebSockeMessageHandler();
    private final WebSocket.Callback disconnectionCallback = new WebSocketDisconnector();
    private final Runnable messageSender = new MessageSender();
    private final BlockingQueue<JSONObject> eventQueue = new LinkedBlockingQueue<JSONObject>();

    /**
     * Repeater callback that repeat messages between clients.
     */
    private class WebSockeMessageHandler implements WebSocket.Callback
    {
        @Override
        public void call(WebSocket sock)
        {
            onMessage(sock);
        }
    }

    /**
     * Disconnection callback that remove the connections and sockets from the state maps.
     */
    private class WebSocketDisconnector implements WebSocket.Callback
    {
        @Override
        public void call(WebSocket sock)
        {
            onDisconnect(sock);
        }
    }

    /**
     * Sender thread.
     */
    private class MessageSender implements Runnable
    {
        @Override
        public void run()
        {
            sendPendingMessage();
        }
    }

    @Override
    public void initialize() throws InitializationException
    {
        // Start the sender thread
        Thread pendingSenderTask = new Thread(messageSender);
        pendingSenderTask.setName("Security Cache Inspector Message Sender Thread");
        pendingSenderTask.setDaemon(true);
        pendingSenderTask.setPriority(Thread.MIN_PRIORITY);
        pendingSenderTask.start();
    }

    /**
     * @return a callback to handle socket message.
     */
    private WebSocket.Callback getMessageCallback()
    {
        return messageCallback;
    }

    /**
     * @return a callback to handle socket disconnections.
     */
    private WebSocket.Callback getDisconnectCallback()
    {
        return disconnectionCallback;
    }

    @Override
    public void onWebSocketConnect(WebSocket connection)
    {
        logger.debug("Adding a new connection");
        add(connection);
    }

    /**
     * @return the count of currently connected sockets.
     */
    protected int getConnectedSocketCount()
    {
        return sockets.size();
    }

    /**
     * Send an error message to a given socket.
     * @param socket the socket.
     * @param errorMessage the message to be sent.
     */
    protected void sendErrorMessage(WebSocket socket, String errorMessage)
    {
        JSONObject json = new JSONObject();
        json.put(TYPE_PROPERTY, "error");
        json.put("message", "Admin access right is require to access this service.");
        socket.send(json.toString());
    }

    /**
     * Build a node message.
     * @param type the message type.
     * @param node the affected node.
     * @return a json message.
     */
    protected JSONObject buildMessage(String type, JSONObject node)
    {
        JSONObject json = new JSONObject();
        json.put(TYPE_PROPERTY, type);
        json.put(NODE_PROPERTY, node);
        return json;
    }

    /**
     * Send a single node message to a given socket.
     * @param socket the socket.
     * @param type the message type.
     * @param node the array of nodes.
     */
    protected void sendNodeMessage(WebSocket socket, String type, JSONObject node)
    {
        socket.send(buildMessage(type, node).toString());
    }

    /**
     * Build a node message.
     * @param type the message type.
     * @param nodes the affected nodes.
     * @return a json message.
     */
    protected JSONObject buildMessage(String type, JSONArray nodes)
    {
        JSONObject json = new JSONObject();
        json.put(TYPE_PROPERTY, type);
        json.put(NODES_PROPERTY, nodes);
        return json;
    }

    /**
     * Send a multi-nodes message to a given socket.
     * @param socket the socket.
     * @param type the message type.
     * @param nodes the array of nodes.
     */
    protected void sendNodeMessage(WebSocket socket, String type, JSONArray nodes)
    {
        socket.send(buildMessage(type, nodes).toString());
    }

    /**
     * Add a new socket to the service if the user is allowed to access the service. If the user is really joining the
     * session now, a init-connection message may be sent to the user. If the user is already in session with that
     * socket, it is a no-op.
     *
     * @param socket the socket to be added.
     * @return the socket.
     */
    private WebSocket add(WebSocket socket)
    {
        DocumentReference user = socket.getUser();

        if (!isAllowed(user)) {
            logger.debug("User [{}] has not enough right to access this service.", user);
            sendErrorMessage(socket, "You do not required rights to access this service.");
            return null;
        }

        logger.debug("Connect user [{}] with socket [{}].", user, socket.hashCode());
        if (sockets.add(socket)) {
            socket.onMessage(getMessageCallback());
            socket.onDisconnect(getDisconnectCallback());

            connect(socket);
        }
        return socket;
    }

    /**
     * @return true if the given user is allowed to connect.
     */
    protected abstract boolean isAllowed(DocumentReference user);

    /**
     * @return the initial message that should be send to new connections.
     */
    protected abstract void connect(WebSocket socket);

    /**
     * Reply to user message.
     * @param socket the socket to retrieve a message from.
     */
    private void onMessage(WebSocket socket)
    {
        String msg = socket.recv();
        DocumentReference sender = socket.getUser();

        if (msg == null) {
            logger.error("No message received from user [{}] with socket [{}].",
                sender, socket.hashCode());
            return;
        }

        JSONObject json;
        try {
            json = JSONObject.fromObject(msg);
        } catch (JSONException e) {
            logger.error("Unable to parse message from user [{}] with socket [{}]. Message dropped: [{}]",
                sender, socket.hashCode(), msg, e);
            return;
        }

        String reply = getReplyForMessage(sender, json);
        if (reply == null) {
            logger.error("Invalid message from user [{}] with socket [{}]. Message dropped: [{}]",
                sender, socket.hashCode(), msg);
            return;
        }

        socket.send(reply);
    }

    /**
     * @return the reply for message.
     */
    protected abstract String getReplyForMessage(DocumentReference sender, JSONObject json);

    /**
     * Disconnect the given socket from the service if it match the socket of the user for this session.
     * @param socket the socket to disconnect.
     */
    private void onDisconnect(WebSocket socket)
    {
        logger.debug("Disconnect user [{}] with socket [{}].", socket.getUser(), socket.hashCode());
        sockets.remove(socket);
        disconnect(socket);
    }

    /**
     * @return the reply for message.
     */
    protected abstract void disconnect(WebSocket user);

    /**
     * Call this function to notify about a node change event.
     * @param event the type of event.
     * @param node the node affected.
     */
    protected void onNodeEvent(String event, JSONObject node)
    {
        try {
            eventQueue.put(buildMessage(event, node));
        } catch (InterruptedException e) {
            logger.warn("Unable to queue events for updating viewers.");
        }
    }

    private void sendPendingMessage()
    {
        String type = null;
        JSONArray nodes = new JSONArray();
        try {
            while (true) {
                JSONObject event;
                if (type == null) {
                    event = eventQueue.take();
                } else {
                    event = eventQueue.poll(500, TimeUnit.MILLISECONDS);

                    if (event == null || !type.equals(event.get(TYPE_PROPERTY))) {
                        sendToAll(buildMessage(type, nodes).toString());
                        type = null;
                        nodes.clear();
                    }
                }

                if (event != null) {
                    type = (String) event.get(TYPE_PROPERTY);
                    nodes.add(event.get(NODE_PROPERTY));
                }
            }
        } catch (InterruptedException ex) {
            logger.warn("Security inspector message sender terminated.");
        }
    }

    private void sendToAll(String msg)
    {
        for (WebSocket socket : sockets) {
            socket.send(msg);
        }
    }
}
