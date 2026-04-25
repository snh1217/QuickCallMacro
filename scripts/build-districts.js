#!/usr/bin/env node
/**
 * 법정동코드 전체자료(TSV)를 읽어 시/도 → 시/군/구 → 동/읍/면 매핑 JSON 생성.
 *
 * 입력: scripts/data/beopjeongdong-raw.txt
 *   (출처: https://gist.github.com/FinanceData/4b0a6e1818cea9e77496e57b84bb4565)
 *
 * 출력: app/src/main/assets/districts.json
 *
 * 규칙:
 *  - 폐지 != "존재" 행 제외
 *  - 코드: SS GGG DDD RR (시도2 시군구3 읍면동3 리2)
 *  - 리 단위(RR != "00") 제외
 *  - 동 단위(DDD != "000")만 수집
 *  - 시도/시군구 헤더 행은 키 결정용으로 별도 사용
 *
 * 키 형식:
 *  - "{시도단축}/{시군구}"  예: "경기/용인시"
 *  - 3단계인 경우 "{시도단축}/{시군}/{구}"  예: "경기/수원시/영통구"
 */

const fs = require('fs');
const path = require('path');

const SIDO_SHORT = {
  '서울특별시': '서울', '부산광역시': '부산', '대구광역시': '대구',
  '인천광역시': '인천', '광주광역시': '광주', '대전광역시': '대전',
  '울산광역시': '울산', '세종특별자치시': '세종',
  '경기도': '경기', '강원도': '강원', '강원특별자치도': '강원',
  '충청북도': '충북', '충청남도': '충남',
  '전라북도': '전북', '전북특별자치도': '전북',
  '전라남도': '전남', '경상북도': '경북', '경상남도': '경남',
  '제주특별자치도': '제주', '제주도': '제주',
};

function shortSido(name) {
  return SIDO_SHORT[name] || name;
}

const INPUT = path.resolve(__dirname, 'data', 'beopjeongdong-raw.txt');
const OUTPUT = path.resolve(__dirname, '..', 'app', 'src', 'main', 'assets', 'districts.json');

const raw = fs.readFileSync(INPUT, 'utf8');
const lines = raw.split(/\r?\n/);

// header 첫 행 스킵
const dataLines = lines.slice(1).filter(Boolean);

// 키별 dong 모음
const map = new Map();
let dongCount = 0, gunguCount = 0, sidoCount = 0;

for (const line of dataLines) {
  const cols = line.split('\t');
  if (cols.length < 3) continue;
  const code = cols[0].trim();
  const name = cols[1].trim();
  const status = cols[2].trim();
  if (status !== '존재') continue;
  if (!/^\d{10}$/.test(code)) continue;

  const ri = code.slice(8, 10);
  const dong = code.slice(5, 8);
  const gu = code.slice(2, 5);

  if (ri !== '00') continue; // 리 단위 제외

  const tokens = name.split(/\s+/);

  if (gu === '000') {
    // 시/도 헤더 — 키만 인식
    sidoCount++;
    continue;
  }
  if (dong === '000') {
    // 시/군/구 헤더
    gunguCount++;
    // 사전에 키 등록 (자식 동이 없어도 빈 배열로 표시 가능 — 우선은 안 함)
    continue;
  }

  // 동/읍/면 행
  if (tokens.length < 3) continue;

  // 마지막 토큰이 동/읍/면 이름
  const dongName = tokens[tokens.length - 1];
  // 부모 경로 = 시도 + 시군구 (1~2 토큰)
  const sidoFull = tokens[0];
  const sidoS = shortSido(sidoFull);
  const middle = tokens.slice(1, tokens.length - 1).join('/'); // "수원시" 또는 "수원시/영통구"
  const key = `${sidoS}/${middle}`;

  if (!map.has(key)) map.set(key, new Set());
  map.get(key).add(dongName);
  dongCount++;
}

// 정렬된 일반 객체로 변환
const sortedKeys = Array.from(map.keys()).sort((a, b) => a.localeCompare(b, 'ko'));
const out = {};
for (const k of sortedKeys) {
  out[k] = Array.from(map.get(k)).sort((a, b) => a.localeCompare(b, 'ko'));
}

// 결과 검증/요약
console.log(`시도 헤더: ${sidoCount}, 시군구 헤더: ${gunguCount}, 동/읍/면: ${dongCount}`);
console.log(`고유 시군구 키: ${sortedKeys.length}`);

fs.mkdirSync(path.dirname(OUTPUT), { recursive: true });
const jsonStr = JSON.stringify(out);
fs.writeFileSync(OUTPUT, jsonStr, 'utf8');
const sizeKb = (jsonStr.length / 1024).toFixed(1);
console.log(`출력: ${OUTPUT}  (${sizeKb} KB)`);
