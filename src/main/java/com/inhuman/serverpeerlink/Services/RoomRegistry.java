package com.inhuman.serverpeerlink.Services;

import com.inhuman.serverpeerlink.Models.Response;
import com.inhuman.serverpeerlink.Models.ResponseType;
import com.inhuman.serverpeerlink.Models.Room;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class RoomRegistry {
    //room code to room {peer1, peer2}
    private final RedisTemplate<String, Room> roomCodeToRoom;
    //sessionId to session
    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    //sessionId to room code
    private final StringRedisTemplate sessionToRoomCode;

    @Value("${roomCode.charSet}")
    private String charSet;

    private final String rc = "rc:";
    private final String sid = "sid:";

    @Value("${roomCode.length}")
    private int codeLen;

    public String generateRoomCode(){
        StringBuilder roomCode = new StringBuilder();
        Random rand = new Random();

        while (roomCode.length() < codeLen)
            roomCode.append(charSet.charAt(rand.nextInt(charSet.length())));

        //regenerate if the rc is duplicate, that is already present
        if(roomCodeToRoom.hasKey(rc + roomCode))
            return generateRoomCode();
        return roomCode.toString();
    }

    public boolean roomExists(String roomCode){
        return roomCodeToRoom.hasKey(rc + roomCode);
    }

    public void createRoom(String roomCode){
        roomCodeToRoom.opsForValue().set(rc + roomCode, new Room(roomCode));
    }

    //BEFORE JOINING, CHECK IF THE USER IS ALREADY IN A ROOM, IF YES THEN CLOSE THE PREVIOUS ROOM
    public synchronized Response joinRoom(WebSocketSession session, String username, String roomCode) {
        //get the room from the mapping
        Room room = roomCodeToRoom.opsForValue().get(rc + roomCode);

        //if room is null then create a room and add the peer
        if(room == null)
            return new Response(ResponseType.ERROR, "Room does not exists");

        //check if the room has an empty slot
        if(room.getSessionIdPeer1() == null) {
            room.setSessionIdPeer1(session.getId());
            sessions.put(room.getSessionIdPeer1(), session);
            room.setPeer1Name(username);
        }
        else if(room.getSessionIdPeer2() == null) {
            room.setSessionIdPeer2(session.getId());
            sessions.put(room.getSessionIdPeer2(), session);
            room.setPeer2Name(username);
        }
        else
            return new Response(ResponseType.ERROR, "Room is Full");

        roomCodeToRoom.opsForValue().set(rc + roomCode, room);
        sessionToRoomCode.opsForValue().set(sid + session.getId(), roomCode);
        setSession(session.getId(), session);
        return new Response(ResponseType.JOINED, roomCode);
    }

    //gets the other peer
    public WebSocketSession getConcernedPeer(WebSocketSession currPeer, String roomCode) {
        Room room = roomCodeToRoom.opsForValue().get(rc + roomCode);

        if(room == null)
            return null;

        if(currPeer == null){
            if(room.getSessionIdPeer1() == null)
                return getSession(room.getSessionIdPeer2());
            if(room.getSessionIdPeer2() == null)
                return getSession(room.getSessionIdPeer1());

        }else{
            if(room.getSessionIdPeer1().equals(currPeer.getId()))
                return getSession(room.getSessionIdPeer2());

            if(room.getSessionIdPeer2().equals(currPeer.getId()))
                return getSession(room.getSessionIdPeer1());
        }
        return null;
    }

    public void close(WebSocketSession session){
        //get room code and remove from session to room code
        String roomCode = sessionToRoomCode.opsForValue().getAndDelete(sid + session.getId());
        //get the room
        Room room = roomCodeToRoom.opsForValue().get(rc + roomCode);
        if(room == null)
            return;

        //remove the peer
        if(getSession(room.getSessionIdPeer1()) == session) {
            room.setPeer1Name(null);
            room.setSessionIdPeer1(null);
        } else if (getSession(room.getSessionIdPeer2()) == session) {
            room.setSessionIdPeer2(null);
            room.setPeer2Name(null);
        }

        //remove the sessionId
        sessions.remove(session.getId());

        //the room is now empty?
        if(room.getSessionIdPeer1() == null && room.getSessionIdPeer2() == null)
            roomCodeToRoom.delete(rc + roomCode);
        else
            roomCodeToRoom.opsForValue().set(rc + roomCode, room);
    }

    public String sessionToRoomCode(WebSocketSession session){
        return sessionToRoomCode.opsForValue().get(sid + session.getId());
    }

    public String getPeerName(WebSocketSession session, String roomCode) {
        Room room = roomCodeToRoom.opsForValue().get(rc + roomCode);
        if(room == null)
            return null;

        if(getSession(room.getSessionIdPeer1()) == session)
            return room.getPeer1Name();
        if(getSession(room.getSessionIdPeer2()) == session)
            return room.getPeer2Name();

        return null;
    }

    private WebSocketSession getSession(String sessionId){
        if (sessionId == null) return null;
        return sessions.get(sessionId);
    }

    private void setSession(String sessionId, WebSocketSession session){
        sessions.put(sessionId, session);
    }
}
