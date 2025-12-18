package com.inhuman.serverpeerlink.Services;

import com.inhuman.serverpeerlink.Models.MessageType;
import com.inhuman.serverpeerlink.Models.WebRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

@Service
@Slf4j
@RequiredArgsConstructor
public class SignalingService {
    private final ObjectMapper objectMapper;
    private final RoomRegistry roomRegistry;

    public void handle(WebSocketSession session, TextMessage message) throws IOException {
        //parse the text message into custom websocket request object
        WebRequest webRequest = objectMapper.readValue(message.getPayload(), WebRequest.class);

        //check the type of request and take action accordingly
        switch(webRequest.getMessageType()) {
            case MessageType.CREATE:
                manageCreate(session, webRequest);break;

            case MessageType.JOIN:
                manageJoin(session, webRequest);break;

            case MessageType.LEAVE:
                close(session);break;

            case MessageType.OFFER, MessageType.ANSWER, MessageType.ICE:
                manageSignal(session, webRequest);break;

            default:
                log.warn("Invalid message type: {}", webRequest.getMessageType());
                sendMessage(session,"[ERROR] Invalid request");
        }
    }

    private void manageCreate(WebSocketSession session, WebRequest req) {
        //generate a room code that is not being used right now then:
        //add the session in the room
        String rc = roomRegistry.generateRoomCode();

        req.setRoomCode(rc);

        //create room
        roomRegistry.createRoom(rc);
        sendMessage(session,"[INFO] Room Created with room code: " + rc);

        //manage join as usual
        manageJoin(session, req);
    }

    private void manageJoin(WebSocketSession session, WebRequest req) {
        //check roomCode
        if(!roomRegistry.roomExists(req.getRoomCode())) {
            sendMessage(session, "[ERROR] Room does not exists");
            return;
        }
        //join room code and return the response
        sendMessage(session,roomRegistry.joinRoom(session, req.getUsername(), req.getRoomCode()));

        //also notify the other peer
        WebSocketSession otherPeer = roomRegistry.getConcernedPeer(session, req.getRoomCode());
        if(otherPeer != null)
            sendMessage(otherPeer,"[INFO] " + req.getUsername() + " joined the room");
    }

    private void manageSignal(WebSocketSession currPeer, WebRequest req) {
        //get the receiver
        WebSocketSession receiver = roomRegistry.getConcernedPeer(currPeer, req.getRoomCode());

        //either room is null or no other peer in the room
        if(receiver == null || !receiver.isOpen()) {
            sendMessage(currPeer, "[ERROR] Room does not exists or No other peer");
        }

        //send the message to the receiver
        sendMessage(receiver,"[" + req.getMessageType().toString() + "] " +req.getMessage());
   }

    public void close(WebSocketSession session) throws IOException {
        //get room code before removing the session
        String roomCode = roomRegistry.sessionToRoomCode(session);

        //if the user was not in a room, but just connected
        if(roomCode == null) {
            session.close();
            return;
        }

        //get the peer name
        String dcPeer = roomRegistry.getPeerName(session, roomCode);

        //close this connection
        roomRegistry.close(session);

        //close the connection
        session.close();

        //notify other peers
        WebSocketSession otherPeer = roomRegistry.getConcernedPeer(null, roomCode);
        if(otherPeer != null)
            sendMessage(otherPeer, "[INFO] " + dcPeer + " left the room");
    }

    private void sendMessage(WebSocketSession session, String message){
        try {
            session.sendMessage(new TextMessage(message));
        } catch (IOException e) {
            log.error("Error while sending message", e);
        }
    }

}
