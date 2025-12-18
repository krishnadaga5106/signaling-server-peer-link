package com.inhuman.serverpeerlink.Models;

import lombok.Data;
import org.springframework.web.socket.WebSocketSession;

@Data
public class Room {
    private String roomCode;
    private WebSocketSession peer1;
    private WebSocketSession peer2;
    private String peer1Name;
    private String peer2Name;

    public Room(String roomCode) {
        this.roomCode = roomCode;
    }
}
