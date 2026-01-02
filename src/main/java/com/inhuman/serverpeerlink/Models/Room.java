package com.inhuman.serverpeerlink.Models;

import lombok.Data;

@Data
public class Room {
    private String roomCode;
    private String sessionIdPeer1;
    private String sessionIdPeer2;
    private String peer1Name;
    private String peer2Name;

    public Room(String roomCode) {
        this.roomCode = roomCode;
    }
}
