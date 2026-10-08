"use client";

import { Canvas, useFrame, useThree } from "@react-three/fiber";
import {
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type RefObject,
} from "react";
import {
  Color,
  ConeGeometry,
  DoubleSide,
  Quaternion,
  ShaderMaterial,
  SphereGeometry,
  Vector3,
  type Group,
} from "three";

import type { MonsterAppearance } from "../model/appearance";

/** 맞는 반응의 길이(초). HP 바 흔들림(`HIT_REACTION_MS`)과 같은 0.4초다. */
const HIT_SECONDS = 0.4;

const VERTEX_SHADER = /* glsl */ `
  varying vec3 vNormal;
  varying vec3 vObjectPosition;
  varying vec3 vViewPosition;

  void main() {
    vNormal = normalize(normalMatrix * normal);
    vObjectPosition = position;
    vec4 viewPosition = modelViewMatrix * vec4(position, 1.0);
    vViewPosition = viewPosition.xyz;
    gl_Position = projectionMatrix * viewPosition;
  }
`;

/**
 * 몸 셰이더: 부드러운 램버트 음영과 테두리 빛, 금(보로노이 경계), 맞을 때 깜빡임.
 * uCrack(0~1)이 클수록 금이 간 영역이 넓어지고 금이 굵어진다.
 */
const FRAGMENT_SHADER = /* glsl */ `
  uniform vec3 uColor;
  uniform float uCrack;
  uniform float uFlash;
  uniform float uSeed;

  varying vec3 vNormal;
  varying vec3 vObjectPosition;
  varying vec3 vViewPosition;

  vec3 hash3(vec3 p) {
    p = vec3(
      dot(p, vec3(127.1, 311.7, 74.7)),
      dot(p, vec3(269.5, 183.3, 246.1)),
      dot(p, vec3(113.5, 271.9, 124.6))
    );
    return fract(sin(p) * 43758.5453123);
  }

  float hash1(vec3 p) {
    return fract(sin(dot(p, vec3(12.9898, 78.233, 37.719))) * 43758.5453);
  }

  // 가장 가까운 두 세포 중심까지 거리의 차. 0에 가까우면 세포 경계(금)다.
  float voronoiEdge(vec3 x) {
    vec3 cell = floor(x);
    vec3 local = fract(x);
    float d1 = 8.0;
    float d2 = 8.0;
    for (int k = -1; k <= 1; k++) {
      for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
          vec3 offset = vec3(float(i), float(j), float(k));
          vec3 r = offset + hash3(cell + offset) - local;
          float d = dot(r, r);
          if (d < d1) {
            d2 = d1;
            d1 = d;
          } else if (d < d2) {
            d2 = d;
          }
        }
      }
    }
    return sqrt(d2) - sqrt(d1);
  }

  void main() {
    vec3 normal = normalize(vNormal);
    vec3 light = normalize(vec3(0.35, 0.8, 0.55));
    float diffuse = max(dot(normal, light), 0.0);
    vec3 color = uColor * (0.5 + 0.6 * diffuse);

    vec3 view = normalize(-vViewPosition);
    float rim = pow(1.0 - max(dot(normal, view), 0.0), 2.5);
    color += vec3(rim * 0.22);

    if (uCrack > 0.0) {
      vec3 p = vObjectPosition * 2.4 + uSeed;
      float edge = voronoiEdge(p);
      float width = 0.03 + 0.07 * uCrack;
      float line = 1.0 - smoothstep(width * 0.5, width, edge);
      float region = hash1(floor(vObjectPosition * 1.6 + uSeed));
      line *= step(region, uCrack);
      color = mix(color, color * 0.22, line);
    }

    color = mix(color, vec3(1.0), uFlash * 0.75);
    gl_FragColor = vec4(color, 1.0);
    #include <colorspace_fragment>
  }
`;

function createBodyMaterial(): ShaderMaterial {
  return new ShaderMaterial({
    vertexShader: VERTEX_SHADER,
    fragmentShader: FRAGMENT_SHADER,
    uniforms: {
      uColor: { value: new Color() },
      uCrack: { value: 0 },
      uFlash: { value: 0 },
      uSeed: { value: 0 },
    },
  });
}

function setLookUniforms(
  material: ShaderMaterial,
  color: string,
  crack: number,
  seed: number,
): void {
  (material.uniforms.uColor.value as Color).set(color);
  material.uniforms.uCrack.value = crack;
  material.uniforms.uSeed.value = seed;
}

function useBodyMaterial(look: MonsterAppearance): ShaderMaterial {
  const material = useMemo(() => createBodyMaterial(), []);
  const { color, crack, hue } = look;

  useLayoutEffect(() => {
    setLookUniforms(material, color, crack, hue * 0.013);
  }, [material, color, crack, hue]);

  useEffect(() => () => material.dispose(), [material]);
  return material;
}

/** 구 표면에 고르게 흩어진 방향(피보나치 구). 불안의 가시를 꽂을 자리다. */
function spikeDirections(count: number): Vector3[] {
  const directions: Vector3[] = [];
  const golden = Math.PI * (3 - Math.sqrt(5));
  for (let i = 0; i < count; i += 1) {
    const y = 1 - (i / (count - 1)) * 2;
    const radius = Math.sqrt(1 - y * y);
    const theta = golden * i;
    const direction = new Vector3(
      Math.cos(theta) * radius,
      y,
      Math.sin(theta) * radius,
    );
    // 얼굴(앞쪽 가운데)과 바닥은 비운다
    const onFace = direction.z > 0.45 && Math.abs(direction.y) < 0.55;
    if (!onFace && direction.y > -0.75) {
      directions.push(direction);
    }
  }
  return directions;
}

const UP = new Vector3(0, 1, 0);
/** 여러 번 쓰는 도형은 모듈에서 한 번만 만들어 함께 쓴다. 메시를 지울 때 버리지 않게 `dispose={null}`로 붙인다. */
const SPIKE_GEOMETRY = new ConeGeometry(0.14, 0.42, 12);
const UNIT_SPHERE = new SphereGeometry(1, 24, 16);

const SPIKES = spikeDirections(26).map((direction) => ({
  position: direction.clone().multiplyScalar(0.92).toArray(),
  quaternion: new Quaternion().setFromUnitVectors(UP, direction).toArray(),
}));

function Body({
  look,
  material,
}: {
  look: MonsterAppearance;
  material: ShaderMaterial;
}) {
  switch (look.shape) {
    case "spiky":
      return (
        <group>
          <mesh material={material}>
            <sphereGeometry args={[0.85, 48, 32]} />
          </mesh>
          {SPIKES.map((spike, index) => (
            <mesh
              key={index}
              material={material}
              position={spike.position}
              quaternion={spike.quaternion}
              geometry={SPIKE_GEOMETRY}
              dispose={null}
            />
          ))}
        </group>
      );
    case "droop":
      return (
        <group>
          {/* 축 처진 물방울: 아래로 퍼진 몸과 바닥에 녹아내린 자국 */}
          <mesh
            material={material}
            position={[0, -0.12, 0]}
            scale={[1.12, 0.82, 1]}
          >
            <sphereGeometry args={[0.9, 48, 32]} />
          </mesh>
          <mesh
            material={material}
            position={[0, 0.55, 0]}
            scale={[0.55, 0.7, 0.55]}
          >
            <sphereGeometry args={[0.6, 32, 24]} />
          </mesh>
          <mesh
            material={material}
            position={[0, -0.78, 0.05]}
            scale={[1.45, 0.16, 1.25]}
          >
            <sphereGeometry args={[0.85, 40, 16]} />
          </mesh>
          <mesh
            material={material}
            position={[0.55, -0.62, 0.62]}
            scale={[0.6, 1, 0.6]}
          >
            <sphereGeometry args={[0.16, 20, 16]} />
          </mesh>
        </group>
      );
    case "curled":
      return (
        <group>
          <mesh material={material}>
            <sphereGeometry args={[0.85, 48, 32]} />
          </mesh>
          {/* 무릎을 끌어안은 두 팔 */}
          <mesh
            material={material}
            position={[-0.34, -0.42, 0.66]}
            scale={[1.2, 0.75, 0.8]}
          >
            <sphereGeometry args={[0.24, 24, 16]} />
          </mesh>
          <mesh
            material={material}
            position={[0.34, -0.42, 0.66]}
            scale={[1.2, 0.75, 0.8]}
          >
            <sphereGeometry args={[0.24, 24, 16]} />
          </mesh>
        </group>
      );
    case "bowed":
      return (
        <group>
          <mesh material={material} position={[0, -0.35, 0]}>
            <capsuleGeometry args={[0.6, 0.45, 12, 32]} />
          </mesh>
          {/* 앞으로 숙인 머리 */}
          <mesh material={material} position={[0, 0.45, 0.32]}>
            <sphereGeometry args={[0.55, 40, 28]} />
          </mesh>
        </group>
      );
    case "angular":
      return (
        <group>
          <mesh material={material} rotation={[0.3, 0.4, 0]}>
            <dodecahedronGeometry args={[0.95, 0]} />
          </mesh>
          {/* 뿔 */}
          <mesh
            material={material}
            position={[-0.42, 0.82, 0.1]}
            rotation={[0, 0, 0.45]}
          >
            <coneGeometry args={[0.16, 0.42, 4]} />
          </mesh>
          <mesh
            material={material}
            position={[0.42, 0.82, 0.1]}
            rotation={[0, 0, -0.45]}
          >
            <coneGeometry args={[0.16, 0.42, 4]} />
          </mesh>
        </group>
      );
  }
}

/** 얼굴이 붙는 자리(몸 앞면). 고개 숙인 몸은 숙인 머리에, 물방울은 위쪽 몸에 붙는다. */
const FACE: Record<
  MonsterAppearance["shape"],
  { position: [number, number, number]; tilt: number; spread: number }
> = {
  spiky: { position: [0, 0.08, 0.8], tilt: 0, spread: 0.3 },
  droop: { position: [0, -0.05, 0.86], tilt: 0.1, spread: 0.32 },
  curled: { position: [0, 0.12, 0.78], tilt: 0, spread: 0.3 },
  bowed: { position: [0, 0.32, 0.8], tilt: 0.45, spread: 0.22 },
  angular: { position: [0, 0.1, 0.86], tilt: 0, spread: 0.3 },
};

const EYE_WHITE = "#ffffff";
const INK = "#1f1d2b";
const TEAR = "#7cc7ff";

function Eye({
  x,
  size,
  look,
}: {
  x: number;
  size: number;
  look: MonsterAppearance;
}) {
  if (look.expression === "knocked") {
    return (
      <group position={[x, 0, 0.02]}>
        <mesh rotation={[0, 0, Math.PI / 4]}>
          <boxGeometry args={[size * 1.9, size * 0.38, 0.04]} />
          <meshBasicMaterial color={INK} />
        </mesh>
        <mesh rotation={[0, 0, -Math.PI / 4]}>
          <boxGeometry args={[size * 1.9, size * 0.38, 0.04]} />
          <meshBasicMaterial color={INK} />
        </mesh>
      </group>
    );
  }
  // 무기력은 눈꺼풀이 반쯤 덮였다
  const sleepy = look.shape === "droop";
  const pupil = look.expression === "teary" ? 0.42 : 0.55;
  return (
    <group position={[x, 0, 0]}>
      <mesh
        geometry={UNIT_SPHERE}
        dispose={null}
        scale={[size, size * (sleepy ? 0.6 : 1), size * 0.55]}
      >
        <meshBasicMaterial color={EYE_WHITE} />
      </mesh>
      <mesh
        position={[
          0,
          look.shape === "bowed" ? -size * 0.35 : -size * 0.05,
          size * 0.42,
        ]}
        geometry={UNIT_SPHERE}
        dispose={null}
        scale={[
          size * pupil,
          size * pupil * (sleepy ? 0.6 : 1),
          size * pupil * 0.4,
        ]}
      >
        <meshBasicMaterial color={INK} />
      </mesh>
      <mesh
        position={[size * 0.22, size * 0.25, size * 0.6]}
        geometry={UNIT_SPHERE}
        dispose={null}
        scale={size * 0.16}
      >
        <meshBasicMaterial color={EYE_WHITE} />
      </mesh>
    </group>
  );
}

function Face({ look }: { look: MonsterAppearance }) {
  const face = FACE[look.shape];
  const size = 0.15 * look.eyeScale;
  const { spread } = face;
  const angry = look.shape === "angular";
  const worried = look.expression === "worried" || look.expression === "teary";
  const smile = look.expression === "calm" && !angry && look.shape !== "bowed";

  return (
    <group position={face.position} rotation={[face.tilt, 0, 0]}>
      <Eye x={-spread} size={size} look={look} />
      <Eye x={spread} size={size} look={look} />

      {(angry || worried) && look.expression !== "knocked"
        ? // 눈썹: 짜증은 안쪽이 내려가고, 지치면 안쪽이 올라간다
          [-1, 1].map((side) => (
            <mesh
              key={side}
              position={[side * spread, size * 1.45, 0.03]}
              rotation={[0, 0, side * (angry ? 0.45 : -0.35)]}
            >
              <boxGeometry args={[size * 1.7, size * 0.3, 0.04]} />
              <meshBasicMaterial color={INK} />
            </mesh>
          ))
        : null}

      {/* 입: 기분 좋으면 웃고, 지치면 내려가고, 쓰러지면 일자 */}
      {look.expression === "knocked" ? (
        <mesh position={[0, -size * 1.8, 0.06]}>
          <boxGeometry args={[size * 1.6, size * 0.22, 0.04]} />
          <meshBasicMaterial color={INK} />
        </mesh>
      ) : (
        <mesh
          position={[0, smile ? -size * 1.3 : -size * 2.1, 0.07]}
          rotation={[0, 0, smile ? Math.PI : 0]}
        >
          <torusGeometry args={[size * 0.75, size * 0.14, 8, 20, Math.PI]} />
          <meshBasicMaterial color={INK} side={DoubleSide} />
        </mesh>
      )}

      {look.expression === "teary" ? (
        <mesh
          position={[spread + size * 0.2, -size * 1.4, 0.04]}
          geometry={UNIT_SPHERE}
          dispose={null}
          scale={[size * 0.28, size * 0.4, size * 0.2]}
        >
          <meshBasicMaterial color={TEAR} />
        </mesh>
      ) : null}
    </group>
  );
}

type Pose = {
  x: number;
  y: number;
  rotX: number;
  rotZ: number;
  scaleX: number;
  scaleY: number;
  flash: number;
};

/** t초의 대기 자세. 쓰러졌거나 still이면 움직이지 않는다. */
function idlePose(look: MonsterAppearance, t: number, still: boolean): Pose {
  const pose: Pose = {
    x: 0,
    y: 0,
    rotX: 0,
    rotZ: 0,
    scaleX: 1,
    scaleY: 1,
    flash: 0,
  };
  if (still || look.fallen) {
    return pose;
  }
  const { amplitude: a, frequency: f } = look.motion;
  const wave = Math.sin(t * Math.PI * 2 * f);
  const breath = Math.sin(t * 2.2) * 0.015;
  pose.scaleX += breath;
  pose.scaleY -= breath;
  switch (look.motion.kind) {
    case "tremble":
      pose.x = wave * a;
      pose.rotZ = Math.sin(t * Math.PI * 2 * f * 0.73) * a * 0.6;
      break;
    case "sag":
      pose.scaleY -= a * (0.5 + 0.5 * wave);
      pose.scaleX += a * 0.5 * (0.5 + 0.5 * wave);
      pose.y = -a * 0.6 * (0.5 + 0.5 * wave);
      break;
    case "sway":
      pose.rotZ = wave * a;
      break;
    case "nod":
      pose.rotX = a * (0.5 + 0.5 * wave);
      break;
    case "twitch": {
      const phase = (t * f) % 1;
      if (phase < 0.14) {
        pose.rotZ = Math.sin(phase * 90) * a * 2;
        pose.scaleX += a;
        pose.scaleY -= a;
      }
      break;
    }
  }
  return pose;
}

/** 맞은 지 elapsed초 뒤의 자세: 좌우로 흔들리고 깜빡이며 납작해졌다가 0.4초에 돌아온다. */
function withHit(pose: Pose, elapsed: number): Pose {
  const fade = 1 - elapsed / HIT_SECONDS;
  return {
    ...pose,
    x: pose.x + Math.sin(elapsed * 70) * 0.12 * fade,
    flash: Math.sin(elapsed * 45) > 0 ? fade : 0,
    scaleX: pose.scaleX * (1 + 0.06 * fade),
    scaleY: pose.scaleY * (1 - 0.06 * fade),
  };
}

function applyPose(group: Group, material: ShaderMaterial, pose: Pose): void {
  group.position.set(pose.x, pose.y, 0);
  group.rotation.set(pose.rotX, 0, pose.rotZ);
  group.scale.set(pose.scaleX, pose.scaleY, 1);
  material.uniforms.uFlash.value = pose.flash;
}

/** 쓰러진 모습. 물방울(무기력)은 바닥에 녹아 퍼지고, 나머지는 옆으로 누워 바닥에 닿는다. */
function fallenPose(look: MonsterAppearance): {
  rotation: [number, number, number];
  position: [number, number, number];
  scale?: [number, number, number];
} {
  if (!look.fallen) {
    return { rotation: [0, 0, 0], position: [0, 0, 0] };
  }
  if (look.shape === "droop") {
    return {
      rotation: [0, 0, 0],
      position: [0, -0.5 * look.scale, 0],
      scale: [look.scale * 1.25, look.scale * 0.5, look.scale * 1.15],
    };
  }
  return { rotation: [0, 0, (-Math.PI / 2) * 0.92], position: [0, -0.42, 0] };
}

/** 대기 움직임과 맞는 반응을 몸에 적용한다. still이면 움직이지 않는다(정지 이미지 캡처). */
function MonsterRig({
  look,
  hp,
  still,
}: {
  look: MonsterAppearance;
  hp: number;
  still: boolean;
}) {
  const rig = useRef<Group>(null);
  const material = useBodyMaterial(look);
  const previousHp = useRef(hp);
  const pendingHit = useRef(false);
  const hitAt = useRef<number | null>(null);
  const invalidate = useThree((state) => state.invalidate);

  useEffect(() => {
    if (hp < previousHp.current) {
      pendingHit.current = true;
      // 요청할 때만 그리는 상태(쓰러짐, 화면 밖)에서도 맞는 반응을 그린다
      invalidate();
    }
    previousHp.current = hp;
  }, [hp, invalidate]);

  useFrame(({ clock }) => {
    if (!rig.current) return;
    const t = clock.elapsedTime;
    if (pendingHit.current) {
      pendingHit.current = false;
      hitAt.current = t;
    }
    let pose = idlePose(look, t, still);
    if (hitAt.current !== null) {
      const elapsed = t - hitAt.current;
      if (elapsed < HIT_SECONDS) {
        pose = withHit(pose, elapsed);
        invalidate();
      } else {
        hitAt.current = null;
      }
    }
    applyPose(rig.current, material, pose);
  });

  return (
    <group scale={look.scale} {...fallenPose(look)}>
      <group ref={rig}>
        <Body look={look} material={material} />
        <Face look={look} />
      </group>
    </group>
  );
}

/** 바닥 그림자. 투명 배경 캡처에서도 몬스터가 떠 보이지 않게 둔다. */
function Shadow({ look }: { look: MonsterAppearance }) {
  return (
    <mesh
      rotation={[-Math.PI / 2, 0, 0]}
      position={[0, -1.05, 0]}
      scale={[look.fallen ? 1.5 : 1.1, 0.9, 1]}
    >
      <circleGeometry args={[0.85 * look.scale, 40]} />
      <meshBasicMaterial
        color="#000000"
        transparent
        opacity={0.12}
        depthWrite={false}
      />
    </mesh>
  );
}

/**
 * 코드로 만든 3D 몬스터(US5, research R11, ADR-0003). 기본 도형과 셰이더로 감정 5종을 그리고,
 * 대기 애니메이션, HP가 줄 때 0.4초 맞는 반응(흔들림과 깜빡임), 쓰러진 모습을 보인다.
 * three가 커서 `next/dynamic`(ssr: false)으로만 불러온다(monster-view.tsx). WebGL 컨텍스트를 만들지
 * 못하면 Canvas가 던지고, monster-view.tsx의 오류 경계가 정지 이미지로 대신한다.
 * `scripts/render-monsters.ts`는 `still`로 멈춘 장면을 캡처해 정지 이미지를 만든다.
 */
export default function Monster3D({
  look,
  hp,
  label,
  still = false,
  onReady,
  className,
}: {
  look: MonsterAppearance;
  hp: number;
  /** 화면 읽기 도구가 읽을 이름. 예: "불안 몬스터, 멀쩡함". */
  label: string;
  /** 움직임 없이 한 장면만 그린다(캡처용). */
  still?: boolean;
  /** 첫 장면을 그린 뒤 부른다(캡처용). */
  onReady?: () => void;
  className?: string;
}) {
  const wrapper = useRef<HTMLDivElement>(null);
  const onScreen = useOnScreen(wrapper);
  // 움직일 때만 매 프레임 그린다. 쓰러졌거나 화면 밖이면 바뀔 때만 그린다.
  const animating = !still && !look.fallen && onScreen;

  return (
    <div ref={wrapper} className={className}>
      <Canvas
        role="img"
        aria-label={label}
        flat
        dpr={[1, 1.5]}
        frameloop={animating ? "always" : "demand"}
        gl={{ alpha: true, antialias: true, preserveDrawingBuffer: still }}
        camera={{ position: [0, 0.35, 5.2], fov: 32 }}
        onCreated={({ gl }) => {
          gl.setClearColor(0x000000, 0);
          if (onReady) {
            requestAnimationFrame(() => requestAnimationFrame(onReady));
          }
        }}
      >
        <Shadow look={look} />
        <MonsterRig look={look} hp={hp} still={still} />
      </Canvas>
    </div>
  );
}

/** 요소가 화면에 보이는지. IntersectionObserver가 없으면 보인다고 본다. */
function useOnScreen(target: RefObject<HTMLElement | null>): boolean {
  const [onScreen, setOnScreen] = useState(true);

  useEffect(() => {
    const element = target.current;
    if (!element || typeof IntersectionObserver === "undefined") {
      return;
    }
    const observer = new IntersectionObserver((entries) => {
      setOnScreen(entries.some((entry) => entry.isIntersecting));
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, [target]);

  return onScreen;
}
