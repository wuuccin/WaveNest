import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { DEMO_CARGO_TYPES } from '../src/api/cargoTypes.js'

const sourcePath = fileURLToPath(new URL(
  '../../backend/wl-contract/src/main/java/com/waterlogistics/contract/order/CargoType.java',
  import.meta.url
))
const source = readFileSync(sourcePath, 'utf8')

// 先剥离注释，避免类级与方法级 Javadoc 里的中文串被当成枚举取值。
const withoutComments = source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '')
const enumBlock = withoutComments.match(/public\s+enum\s+CargoType\s*\{([\s\S]*?);/)?.[1]
assert.ok(enumBlock, '无法定位 CargoType 枚举常量块，解析规则需随源码结构同步更新')

const declared = [...enumBlock.matchAll(/([A-Z][A-Z0-9_]*)\s*\(\s*"([^"]+)"\s*\)/g)]
  .map(match => match[2])
assert.ok(declared.length > 0, '未从 CargoType 解析出任何品类取值，守卫不得静默通过')
assert.deepEqual(
  declared,
  DEMO_CARGO_TYPES,
  '前端 DEMO_CARGO_TYPES 与后端 CargoType 的取值或声明顺序不一致，须同批修改'
)

console.log(`配送品类字典同源检查通过：${declared.join('、')}`)
