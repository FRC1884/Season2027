package org.Griffins1884.frc2027;

import org.Griffins1884.frc2027.GlobalConstants.RobotType;

public class CanIDConstants {
  public static RobotType ROBOT = GlobalConstants.ROBOT;

  // Intake
  public static final int[] INTAKE_PIVOT_IDS = {19, 20};
  public static int[] INTAKE_IDS = {21};
  public static int TOOTH_ROLLOUT_ID = 90;

  // Indexer
  public static int[] INDEXER_IDS = {18};
  public static int SPINDEXER_ID = 91;

  // Shooter
  public static int[] SHOOTER_IDS = {22, 23};
  public static int[] SHOOTER_PIVOT_IDS = {24};

  // Turret
  public static final int TURRET_ID = 25;

  // NOTE: For the 2026 FRC Compbot The Front is the side without the intake

  // Swerve Constants
  public static final int PIGEON_ID =
      switch (ROBOT) {
        case COMPBOT, SIMBOT -> 60;
      };

  // Front Right
  public static final int FRD_ID =
      switch (ROBOT) {
        case COMPBOT, SIMBOT -> 13;
      };
  public static final int FRR_ID =
      switch (ROBOT) {
        case COMPBOT, SIMBOT -> 12;
      };
  public static final int FRR_CANCODER_ID =
      switch (ROBOT) {
        case COMPBOT, SIMBOT -> 4;
      };

  // Front Left
  public static final int FLD_ID =
      switch (ROBOT) {
        case COMPBOT, SIMBOT -> 11;
      };
  public static final int FLR_ID =
      switch (ROBOT) {
        case COMPBOT, SIMBOT -> 10;
      };
  public static final int FLR_CANCODER_ID =
      switch (ROBOT) {
        case COMPBOT, SIMBOT -> 3;
      };

  // Back Right
  public static final int BRD_ID =
      switch (ROBOT) {
        case COMPBOT, SIMBOT -> 14;
      };
  public static final int BRR_ID =
      switch (ROBOT) {
        case COMPBOT, SIMBOT -> 15;
      };
  public static final int BRR_CANCODER_ID =
      switch (ROBOT) {
        case COMPBOT, SIMBOT -> 5;
      };

  // Back Left
  public static final int BLD_ID =
      switch (ROBOT) {
        case COMPBOT, SIMBOT -> 16;
      };
  public static final int BLR_ID =
      switch (ROBOT) {
        case COMPBOT, SIMBOT -> 17;
      };
  public static final int BLR_CANCODER_ID =
      switch (ROBOT) {
        case COMPBOT, SIMBOT -> 6;
      };
}
