package com.inhuman.serverpeerlink.Controllers;

import com.inhuman.serverpeerlink.Models.Room;
import com.inhuman.serverpeerlink.Services.RoomRegistry;
import com.inhuman.serverpeerlink.Services.SignalingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/test")
public class SendMessageTestController {

    private final SignalingService signalingService;
    private final RoomRegistry roomRegistry;

    @GetMapping("/")
    public void sendMessage(@RequestBody String message){
        Map<String, Room> rooms = roomRegistry.getRooms();

        for(String room : rooms.keySet()){
            Room r = rooms.get(room);
            signalingService.sendMessagePub(r.getPeer1(), message);
            signalingService.sendMessagePub(r.getPeer2(), message);
        }
        log.info("sent message to all the sessions.");
    }

    @GetMapping("/get-all")
    public String getAllSessions(){
        Map<String, Room> rooms = roomRegistry.getRooms();
        return rooms.toString();
    }

}
