package com.infy.temiapplication;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.robotemi.sdk.Robot;
import com.robotemi.sdk.listeners.OnRobotReadyListener;
import com.robotemi.sdk.listeners.OnGoToLocationStatusChangedListener;

import java.util.List;

public class MainActivity extends AppCompatActivity implements
        OnRobotReadyListener,
        OnGoToLocationStatusChangedListener {

    private final String goBacklocation = "Pantry";
    private Robot robot;
    private TextView statusText;
    private TextView txtWaiting;
    private DatabaseReference locRef, statusRef;

    private boolean isMoving = false;
    private String lastCommand = "";
    private final Handler navHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        txtWaiting = findViewById(R.id.txtWaiting);
        robot = Robot.getInstance();

        FirebaseDatabase db = FirebaseDatabase.getInstance();
        locRef = db.getReference("location");
        statusRef = db.getReference("status");

        locRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                String target = snapshot.getValue(String.class);
                if (target == null || target.equalsIgnoreCase("none")) {
                    lastCommand = "";
                    return;
                }


                if (!target.equalsIgnoreCase(lastCommand) && !isMoving) {
                    checkAndNavigate(target);
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    private void checkAndNavigate(String target) {
        List<String> knownLocations = robot.getLocations();
        boolean exists = false;
        for (String loc : knownLocations) {
            if (loc.equalsIgnoreCase(target)) {
                exists = true;
                startNavigationSequence(loc);
                break;
            }
        }
        if (!exists) {
            Log.e("Nav", "Location " + target + " not found!");
            statusRef.setValue("Error: Location not found");
        }
    }

    private void startNavigationSequence(String location) {
        isMoving = true;
        lastCommand = location;

        robot.stopMovement();
        robot.tiltAngle(0);

        statusRef.setValue("Preparing to move...");
        statusText.setText("PREPARING...");

        navHandler.postDelayed(() -> {
            Log.d("NavSystem", "Sending goTo: " + location);
            robot.goTo(location);
            statusRef.setValue("Moving to " + location);
            statusText.setText("MOVING TO " + location.toUpperCase());
            if (txtWaiting != null) txtWaiting.setVisibility(View.GONE);
        }, 3000);
    }

    @Override
    public void onGoToLocationStatusChanged(@NonNull String location, @NonNull String status, int id, @NonNull String desc) {
        runOnUiThread(() -> {
            Log.d("NavStatus", "Loc: " + location + " Status: " + status);
            if (status.equalsIgnoreCase("complete")) {
                handleArrival(location);
            } else if (status.equalsIgnoreCase("abort") || status.equalsIgnoreCase("reject")) {
                handleNavigationFailure(location);
            }
        });
    }

    private void handleArrival(String location) {

        isMoving = false;
        lastCommand = "";
        robot.stopMovement();

        if (!location.equalsIgnoreCase(goBacklocation)) {

            statusText.setText("ARRIVED AT " + location.toUpperCase());
            statusRef.setValue("Arrived at " + location);


            locRef.setValue("none");


            navHandler.postDelayed(() -> {
                if (!isMoving) {
                    statusText.setText("RETURNING TO PANTRY...");
                    locRef.setValue(goBacklocation);
                }
            }, 10000);

        } else {
            statusText.setText("READY AT PANTRY");
            statusRef.setValue("idle");
            locRef.setValue("none");
            if (txtWaiting != null) txtWaiting.setVisibility(View.VISIBLE);
        }
    }

    private void handleNavigationFailure(String location) {
        isMoving = false;
        lastCommand = "";
        robot.stopMovement();
        statusRef.setValue("Path Blocked! Retrying...");
        statusText.setText("BLOCKED - RETRYING");

        navHandler.postDelayed(() -> {
            if (!isMoving) {
                locRef.setValue("none");
                navHandler.postDelayed(() -> locRef.setValue(location), 500);
            }
        }, 7000);
    }


@Override
public void onRobotReady(boolean ready) {
    if (ready) {

        robot.hideTopBar(true);

        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        robot.requestToBeKioskApp();

        statusRef.setValue("idle");
        locRef.setValue("none");
    }
}

    @Override protected void onStart() { super.onStart(); robot.addOnRobotReadyListener(this); robot.addOnGoToLocationStatusChangedListener(this); }
    @Override protected void onStop() { robot.removeOnRobotReadyListener(this); robot.removeOnGoToLocationStatusChangedListener(this); super.onStop(); }
}