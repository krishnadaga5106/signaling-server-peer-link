package com.inhuman.serverpeerlink.Services;

import com.inhuman.serverpeerlink.Models.Response;
import com.inhuman.serverpeerlink.Models.ResponseType;
import com.inhuman.serverpeerlink.Models.Room;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RoomRegistry {
    //room code to room {peer1, peer2}
    @Getter //for testing, remove later
    private final ConcurrentHashMap<String, Room> rooms = new ConcurrentHashMap<>();

    //session to room code
    private final ConcurrentHashMap<WebSocketSession, String> sessionToRoomCode = new ConcurrentHashMap<>();

    @Value("${roomCode.charSet}")
    private String charSet;

    @Value("${roomCode.length}")
    private int codeLen;

    public String generateRoomCode(){
        StringBuilder rc = new StringBuilder();
        Random rand = new Random();

        while (rc.length() < codeLen)
            rc.append(charSet.charAt(rand.nextInt(charSet.length())));

        //regenerate if the rc is duplicate, that is already present
        if(rooms.containsKey(rc.toString()))
            return generateRoomCode();
        return rc.toString();
    }

    public boolean roomExists(String roomCode){
        return rooms.containsKey(roomCode);
    }

    public void createRoom(String roomCode){
        rooms.put(roomCode, new Room(roomCode));
    }

    //BEFORE JOINING, CHECK IF THE USER IS ALREADY IN A ROOM, IF YES THEN CLOSE THE PREVIOUS ROOM
    public synchronized Response joinRoom(WebSocketSession session, String username, String roomCode) {
        //get the room from the mapping
        Room room = rooms.get(roomCode);

        //if room is null then create a room and add the peer
        if(room == null)
            return new Response(ResponseType.ERROR, "Room does not exists");

        //check if the room is empty
        if(room.getPeer1() == null) {
            room.setPeer1(session);
            room.setPeer1Name(username);
        }
        else if(room.getPeer2() == null) {
            room.setPeer2(session);
            room.setPeer2Name(username);
        }
        else
            return new Response(ResponseType.ERROR, "Room is Full");

        rooms.put(roomCode, room);
        sessionToRoomCode.put(session, roomCode);
        return new Response(ResponseType.JOINED, roomCode);
    }

    //gets the other peer
    public WebSocketSession getConcernedPeer(WebSocketSession currPeer, String roomCode) {
        Room room = rooms.get(roomCode);

        if(room == null)
            return null;

        if(room.getPeer1() == currPeer)
            return room.getPeer2();

        if(room.getPeer2() == currPeer)
            return room.getPeer1();

        return null;
    }

    public void close(WebSocketSession session){
        //get room code and remove from session to room code
        String roomCode = sessionToRoomCode.remove(session);

        //get the room
        Room room = rooms.get(roomCode);

        if(room == null)
            return;

        //remove the peer
        if(room.getPeer1() == session)
            room.setPeer1(null);
        else if(room.getPeer2() == session)
            room.setPeer2(null);

        //the room is now empty?
        if(room.getPeer1() == null && room.getPeer2() == null)
            rooms.remove(roomCode);
        else
            rooms.put(roomCode, room);
    }

    public String sessionToRoomCode(WebSocketSession session){
        return sessionToRoomCode.get(session);
    }

    public String getPeerName(WebSocketSession session, String roomCode) {
        Room room = rooms.get(roomCode);
        if(room == null)
            return "";

        if(room.getPeer1() == session)
            return room.getPeer1Name();
        if(room.getPeer2() == session)
            return room.getPeer2Name();

        return "";
    }

}
