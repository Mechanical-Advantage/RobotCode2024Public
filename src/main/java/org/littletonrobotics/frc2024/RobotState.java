// Copyright (c) 2024 FRC 6328
// http://github.com/Mechanical-Advantage
//
// Use of this source code is governed by an MIT-style
// license that can be found in the LICENSE file at
// the root directory of this project.

package org.littletonrobotics.frc2024;

import edu.wpi.first.math.*;
import edu.wpi.first.math.geometry.*;
import edu.wpi.first.math.interpolation.*;
import edu.wpi.first.math.kinematics.SwerveDriveKinematics;
import edu.wpi.first.math.kinematics.SwerveDriveWheelPositions;
import edu.wpi.first.math.kinematics.SwerveModulePosition;
import edu.wpi.first.math.numbers.N1;
import edu.wpi.first.math.numbers.N3;
import edu.wpi.first.math.util.Units;
import edu.wpi.first.wpilibj.DriverStation;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.ExtensionMethod;
import org.littletonrobotics.frc2024.subsystems.drive.DriveConstants;
import org.littletonrobotics.frc2024.subsystems.superstructure.arm.ArmConstants;
import org.littletonrobotics.frc2024.util.AllianceFlipUtil;
import org.littletonrobotics.frc2024.util.GeomUtil;
import org.littletonrobotics.frc2024.util.LoggedTunableNumber;
import org.littletonrobotics.frc2024.util.NoteVisualizer;
import org.littletonrobotics.frc2024.util.swerve.ModuleLimits;
import org.littletonrobotics.junction.AutoLogOutput;
import org.littletonrobotics.junction.Logger;

@ExtensionMethod({GeomUtil.class})
public class RobotState {
  public record OdometryObservation(
      SwerveDriveWheelPositions wheelPositions, Rotation2d gyroAngle, double timestamp) {}

  public record VisionObservation(Pose2d visionPose, double timestamp, Matrix<N3, N1> stdDevs) {}

  public record FlywheelSpeeds(double leftSpeed, double rightSpeed) {
    public static FlywheelSpeeds interpolate(FlywheelSpeeds t1, FlywheelSpeeds t2, double v) {
      double leftSpeed = MathUtil.interpolate(t1.leftSpeed(), t2.leftSpeed(), v);
      double rightSpeed = MathUtil.interpolate(t1.rightSpeed(), t2.rightSpeed(), v);
      return new FlywheelSpeeds(leftSpeed, rightSpeed);
    }
  }

  public record AimingParameters(
      Rotation2d driveHeading,
      Rotation2d armAngle,
      double effectiveDistance,
      FlywheelSpeeds flywheelSpeeds) {}

  public record DemoFollowParameters(
      Pose2d targetPose, Rotation2d targetHeading, Rotation2d armAngle) {}

  public record DemoShotParameters(Rotation2d armAngle, FlywheelSpeeds flywheelSpeeds) {}

  private static final LoggedTunableNumber autoLookahead =
      new LoggedTunableNumber("RobotState/AutoLookahead", 0.5);
  private static final LoggedTunableNumber lookahead =
      new LoggedTunableNumber("RobotState/lookaheadS", 0.35);
  private static final LoggedTunableNumber superPoopLookahead =
      new LoggedTunableNumber("RobotState/SuperPoopLookahead", 0.1);
  private static final LoggedTunableNumber closeShootingZoneFeet =
      new LoggedTunableNumber("RobotState/CloseShootingZoneFeet", 10.0);

  private static final double poseBufferSizeSeconds = 2.0;

  private static final double armAngleCoefficient = 57.254371165197;
  private static final double armAngleExponent = -0.593140189605718;

  @AutoLogOutput @Getter @Setter private boolean flywheelAccelerating = false;
  @AutoLogOutput @Getter @Setter private double shotCompensationDegrees = 0.0;

  // Super poop
  private static final InterpolatingDoubleTreeMap superPoopArmAngleMap =
      new InterpolatingDoubleTreeMap();

  static {
    superPoopArmAngleMap.put(Units.feetToMeters(33.52713263758169), 35.0);
    superPoopArmAngleMap.put(Units.feetToMeters(28.31299227120627), 39.0);
    superPoopArmAngleMap.put(Units.feetToMeters(25.587026383435525), 48.0);
  }

  private static final InterpolatingTreeMap<Double, FlywheelSpeeds> superPoopFlywheelSpeedsMap =
      new InterpolatingTreeMap<>(InverseInterpolator.forDouble(), FlywheelSpeeds::interpolate);

  static {
    superPoopFlywheelSpeedsMap.put(
        Units.feetToMeters(33.52713263758169), new FlywheelSpeeds(3500, 4500));
    superPoopFlywheelSpeedsMap.put(
        Units.feetToMeters(28.31299227120627), new FlywheelSpeeds(2800, 3500));
    superPoopFlywheelSpeedsMap.put(
        Units.feetToMeters(25.587026383435525), new FlywheelSpeeds(2500, 3200));
  }

  private static final double autoFarShotCompensationDegrees = 0.0; // 0.6 at NECMP

  public void adjustShotCompensationDegrees(double deltaDegrees) {
    shotCompensationDegrees += deltaDegrees;
  }

  private static RobotState instance;

  public static RobotState getInstance() {
    if (instance == null) instance = new RobotState();
    return instance;
  }

  // Pose Estimation Members
  private Pose2d odometryPose = new Pose2d();
  private Pose2d estimatedPose = new Pose2d();
  private final TimeInterpolatableBuffer<Pose2d> poseBuffer =
      TimeInterpolatableBuffer.createBuffer(poseBufferSizeSeconds);
  @Getter @Setter private Pose2d trajectorySetpoint = new Pose2d();
  private final Matrix<N3, N1> qStdDevs = new Matrix<>(Nat.N3(), Nat.N1());
  // Odometry
  private final SwerveDriveKinematics kinematics;
  private SwerveDriveWheelPositions lastWheelPositions =
      new SwerveDriveWheelPositions(
          new SwerveModulePosition[] {
            new SwerveModulePosition(),
            new SwerveModulePosition(),
            new SwerveModulePosition(),
            new SwerveModulePosition()
          });
  private Rotation2d lastGyroAngle = new Rotation2d();
  private Twist2d robotVelocity = new Twist2d();
  private Twist2d trajectoryVelocity = new Twist2d();

  /** Cached latest aiming parameters. Calculated in {@code getAimingParameters()} */
  private AimingParameters latestParameters = null;

  private AimingParameters latestSuperPoopParameters = null;
  // Demo parameters
  private Pose3d demoTagPose = null;
  private DemoFollowParameters latestDemoParamters = null;

  @Setter @Getter
  private DemoShotParameters demoShotParameters =
      new DemoShotParameters(Rotation2d.fromDegrees(0.0), new FlywheelSpeeds(0.0, 0.0));

  @Setter private BooleanSupplier lookaheadDisable = () -> false;

  private RobotState() {
    for (int i = 0; i < 3; ++i) {
      qStdDevs.set(i, 0, Math.pow(DriveConstants.odometryStateStdDevs.get(i, 0), 2));
    }
    kinematics = DriveConstants.kinematics;

    // Setup NoteVisualizer
    NoteVisualizer.setRobotPoseSupplier(this::getEstimatedPose);
  }

  /** Add odometry observation */
  public void addOdometryObservation(OdometryObservation observation) {
    latestParameters = null;
    latestSuperPoopParameters = null;
    Twist2d twist = kinematics.toTwist2d(lastWheelPositions, observation.wheelPositions());
    lastWheelPositions = observation.wheelPositions();
    // Check gyro connected
    if (observation.gyroAngle != null) {
      // Update dtheta for twist if gyro connected
      twist =
          new Twist2d(
              twist.dx, twist.dy, observation.gyroAngle().minus(lastGyroAngle).getRadians());
      lastGyroAngle = observation.gyroAngle();
    }
    // Add twist to odometry pose
    odometryPose = odometryPose.exp(twist);
    // Add pose to buffer at timestamp
    poseBuffer.addSample(observation.timestamp(), odometryPose);
    // Calculate diff from last odometry pose and add onto pose estimate
    estimatedPose = estimatedPose.exp(twist);
  }

  public void addVisionObservation(VisionObservation observation) {
    latestParameters = null;
    latestSuperPoopParameters = null;
    // If measurement is old enough to be outside the pose buffer's timespan, skip.
    try {
      if (poseBuffer.getInternalBuffer().lastKey() - poseBufferSizeSeconds
          > observation.timestamp()) {
        return;
      }
    } catch (NoSuchElementException ex) {
      return;
    }
    // Get odometry based pose at timestamp
    var sample = poseBuffer.getSample(observation.timestamp());
    if (sample.isEmpty()) {
      // exit if not there
      return;
    }

    // sample --> odometryPose transform and backwards of that
    var sampleToOdometryTransform = new Transform2d(sample.get(), odometryPose);
    var odometryToSampleTransform = new Transform2d(odometryPose, sample.get());
    // get old estimate by applying odometryToSample Transform
    Pose2d estimateAtTime = estimatedPose.plus(odometryToSampleTransform);

    // Calculate 3 x 3 vision matrix
    var r = new double[3];
    for (int i = 0; i < 3; ++i) {
      r[i] = observation.stdDevs().get(i, 0) * observation.stdDevs().get(i, 0);
    }
    // Solve for closed form Kalman gain for continuous Kalman filter with A = 0
    // and C = I. See wpimath/algorithms.md.
    Matrix<N3, N3> visionK = new Matrix<>(Nat.N3(), Nat.N3());
    for (int row = 0; row < 3; ++row) {
      double stdDev = qStdDevs.get(row, 0);
      if (stdDev == 0.0) {
        visionK.set(row, row, 0.0);
      } else {
        visionK.set(row, row, stdDev / (stdDev + Math.sqrt(stdDev * r[row])));
      }
    }
    // difference between estimate and vision pose
    Transform2d transform = new Transform2d(estimateAtTime, observation.visionPose());
    // scale transform by visionK
    var kTimesTransform =
        visionK.times(
            VecBuilder.fill(
                transform.getX(), transform.getY(), transform.getRotation().getRadians()));
    Transform2d scaledTransform =
        new Transform2d(
            kTimesTransform.get(0, 0),
            kTimesTransform.get(1, 0),
            Rotation2d.fromRadians(kTimesTransform.get(2, 0)));

    // Recalculate current estimate by applying scaled transform to old estimate
    // then replaying odometry data
    estimatedPose = estimateAtTime.plus(scaledTransform).plus(sampleToOdometryTransform);
  }

  public void addVelocityData(Twist2d robotVelocity) {
    latestParameters = null;
    this.robotVelocity = robotVelocity;
  }

  public void addTrajectoryVelocityData(Twist2d robotVelocity) {
    latestParameters = null;
    trajectoryVelocity = robotVelocity;
  }

  public AimingParameters getAimingParameters() {
    if (latestParameters != null) {
      // Cache previously calculated aiming parameters. Cache is invalidated whenever new
      // observations are added.
      return latestParameters;
    }

    Transform2d fieldToTarget =
        AllianceFlipUtil.apply(FieldConstants.Speaker.centerSpeakerOpening)
            .toTranslation2d()
            .toTransform2d()
            .plus(FudgeFactors.speaker.getTransform());
    Pose2d fieldToPredictedVehicle;
    if (DriverStation.isAutonomousEnabled()) {
      fieldToPredictedVehicle = getPredictedPose(autoLookahead.get(), autoLookahead.get());

    } else {
      fieldToPredictedVehicle =
          lookaheadDisable.getAsBoolean()
              ? getEstimatedPose()
              : getPredictedPose(lookahead.get(), lookahead.get());
    }
    Logger.recordOutput("RobotState/AimingParameters/PredictedPose", fieldToPredictedVehicle);

    Pose2d fieldToPredictedVehicleFixed =
        new Pose2d(fieldToPredictedVehicle.getTranslation(), new Rotation2d());

    Translation2d predictedVehicleToTargetTranslation =
        fieldToPredictedVehicle.inverse().transformBy(fieldToTarget).getTranslation();
    Translation2d predictedVehicleFixedToTargetTranslation =
        fieldToPredictedVehicleFixed.inverse().transformBy(fieldToTarget).getTranslation();

    Rotation2d targetVehicleDirection = predictedVehicleFixedToTargetTranslation.getAngle();
    double targetDistance = predictedVehicleToTargetTranslation.getNorm();

    double armAngleDegrees = armAngleCoefficient * Math.pow(targetDistance, armAngleExponent);
    double autoFarArmCorrection =
        DriverStation.isAutonomousEnabled() && targetDistance >= Units.inchesToMeters(125)
            ? autoFarShotCompensationDegrees
            : 0.0;
    Logger.recordOutput(
        "RobotState/AimingParameters/AutoFarArmCorrectionDegrees", autoFarArmCorrection);
    latestParameters =
        new AimingParameters(
            targetVehicleDirection,
            Rotation2d.fromDegrees(
                armAngleDegrees + shotCompensationDegrees + autoFarArmCorrection),
            targetDistance,
            new FlywheelSpeeds(0, 0));
    return latestParameters;
  }

  private static final Translation2d superPoopTarget =
      FieldConstants.Subwoofer.centerFace
          .getTranslation()
          .interpolate(FieldConstants.ampCenter, 0.5);

  public AimingParameters getSuperPoopAimingParameters() {
    if (latestSuperPoopParameters != null) {
      return latestSuperPoopParameters;
    }
    Pose2d predictedFieldToRobot =
        getPredictedPose(superPoopLookahead.get(), superPoopLookahead.get());
    Translation2d predictedRobotToTarget =
        AllianceFlipUtil.apply(superPoopTarget).minus(predictedFieldToRobot.getTranslation());
    double effectiveDistance = predictedRobotToTarget.getNorm();
    var flywheelSpeeds = superPoopFlywheelSpeedsMap.get(effectiveDistance);
    var armAngle = Rotation2d.fromDegrees(superPoopArmAngleMap.get(effectiveDistance));

    Translation2d vehicleVelocity =
        new Translation2d(robotVelocity.dx, robotVelocity.dy)
            .rotateBy(predictedRobotToTarget.getAngle().unaryMinus());
    Logger.recordOutput("RobotState/SuperPoopParameters/RadialVelocity", vehicleVelocity.getX());
    double radialVelocity =
        Units.radiansPerSecondToRotationsPerMinute(
                vehicleVelocity.getX() / Units.inchesToMeters(1.5))
            * armAngle.getCos();
    flywheelSpeeds =
        new FlywheelSpeeds(
            flywheelSpeeds.leftSpeed() - radialVelocity,
            flywheelSpeeds.rightSpeed() - radialVelocity);

    latestSuperPoopParameters =
        new AimingParameters(
            predictedRobotToTarget.getAngle(), armAngle, effectiveDistance, flywheelSpeeds);
    return latestSuperPoopParameters;
  }

  public void setDemoTagPose(Pose3d demoTagPose) {
    this.demoTagPose = demoTagPose;
    latestDemoParamters = null;
  }

  private static final LoggedTunableNumber demoTargetDistance =
      new LoggedTunableNumber("RobotState/DemoTargetDistance", 2.0);

  public Optional<DemoFollowParameters> getDemoTagParameters() {
    if (latestDemoParamters != null) {
      // Use cached demo parameters.
      return Optional.of(latestDemoParamters);
    }
    // Return empty optional if no demo tag pose.
    if (demoTagPose == null) return Optional.empty();

    // Calculate target pose.
    Pose2d targetPose =
        demoTagPose
            .toPose2d()
            .transformBy(
                new Transform2d(
                    new Translation2d(demoTargetDistance.get(), 0.0), new Rotation2d(Math.PI)));

    // Calculate heading without movement.
    Translation2d demoTagFixed = demoTagPose.getTranslation().toTranslation2d();
    Translation2d robotToDemoTagFixed = demoTagFixed.minus(getEstimatedPose().getTranslation());
    Rotation2d targetHeading = robotToDemoTagFixed.getAngle();

    // Calculate arm angle.
    double z = demoTagPose.getZ();
    Rotation2d armAngle =
        new Rotation2d(
            robotToDemoTagFixed.getNorm() - ArmConstants.armOrigin.getX(),
            z - ArmConstants.armOrigin.getY());

    latestDemoParamters = new DemoFollowParameters(targetPose, targetHeading, armAngle);
    return Optional.of(latestDemoParamters);
  }

  public ModuleLimits getModuleLimits() {
    return flywheelAccelerating && !DriverStation.isAutonomousEnabled()
        ? DriveConstants.moduleLimitsFlywheelSpinup
        : DriveConstants.moduleLimitsFree;
  }

  public boolean inShootingZone() {
    Pose2d robot = AllianceFlipUtil.apply(getEstimatedPose());
    if (robot.getY() <= FieldConstants.Stage.ampLeg.getY()) {
      return robot.getX() <= FieldConstants.wingX;
    } else {
      return robot.getX() <= FieldConstants.fieldLength / 2.0 + 0.5;
    }
  }

  public boolean inCloseShootingZone() {
    return getEstimatedPose()
            .getTranslation()
            .getDistance(
                AllianceFlipUtil.apply(
                    FieldConstants.Speaker.centerSpeakerOpening.toTranslation2d()))
        < Units.feetToMeters(closeShootingZoneFeet.get());
  }

  /**
   * Reset estimated pose and odometry pose to pose <br>
   * Clear pose buffer
   */
  public void resetPose(Pose2d initialPose) {
    estimatedPose = initialPose;
    odometryPose = initialPose;
    poseBuffer.clear();
  }

  @AutoLogOutput(key = "RobotState/FieldVelocity")
  public Twist2d fieldVelocity() {
    Translation2d linearFieldVelocity =
        new Translation2d(robotVelocity.dx, robotVelocity.dy).rotateBy(estimatedPose.getRotation());
    return new Twist2d(
        linearFieldVelocity.getX(), linearFieldVelocity.getY(), robotVelocity.dtheta);
  }

  @AutoLogOutput(key = "RobotState/EstimatedPose")
  public Pose2d getEstimatedPose() {
    return estimatedPose;
  }

  /**
   * Predicts what our pose will be in the future. Allows separate translation and rotation
   * lookaheads to account for varying latencies in the different measurements.
   *
   * @param translationLookaheadS The lookahead time for the translation of the robot
   * @param rotationLookaheadS The lookahead time for the rotation of the robot
   * @return The predicted pose.
   */
  public Pose2d getPredictedPose(double translationLookaheadS, double rotationLookaheadS) {
    Twist2d velocity = DriverStation.isAutonomousEnabled() ? trajectoryVelocity : robotVelocity;
    return getEstimatedPose()
        .transformBy(
            new Transform2d(
                velocity.dx * translationLookaheadS,
                velocity.dy * translationLookaheadS,
                Rotation2d.fromRadians(velocity.dtheta * rotationLookaheadS)));
  }

  @AutoLogOutput(key = "RobotState/OdometryPose")
  public Pose2d getOdometryPose() {
    return odometryPose;
  }
}
