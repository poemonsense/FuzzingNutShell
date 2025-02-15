/**************************************************************************************
* Copyright (c) 2020 Institute of Computing Technology, CAS
* Copyright (c) 2020 University of Chinese Academy of Sciences
*
* NutShell is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*             http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND, EITHER
* EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT, MERCHANTABILITY OR
* FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package nutcore

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils

import utils._
import difftest._

class Decoder(implicit val p: NutCoreConfig) extends NutCoreModule with HasInstrType {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new CtrlFlowIO))
    val out = Decoupled(new DecodeIO)
    val isWFI = Output(Bool()) // require NutCoreSim to advance mtime when wfi to reduce the idle time in Linux
    val isBranch = Output(Bool())
    val sfence_vma_invalid = Input(Bool())
    val wfi_invalid = Input(Bool())
  })
  val expander = Module(new RVCExpander(XLEN))
  expander.io.in := io.in.bits.instr

  val instr = expander.io.out.bits
  val isRVC = expander.io.rvc
  val isIllegalRVC = if (HasCExtension) false.B else expander.io.rvc

  val decodeList = ListLookup(instr, Instructions.DecodeDefault, Instructions.DecodeTable)
  val hasIntr = Wire(Bool())
  val instrType :: fuType :: fuOpType :: Nil = // insert Instructions.DecodeDefault when interrupt comes
    Instructions.DecodeDefault.zip(decodeList).map{case (intr, dec) => Mux(hasIntr || io.in.bits.exceptionVec(instrPageFault) || io.out.bits.cf.exceptionVec(instrAccessFault), intr, dec)}

  io.out.bits := DontCare

  io.out.bits.ctrl.fuType := fuType
  io.out.bits.ctrl.fuOpType := fuOpType

  val SrcTypeTable = List(
    InstrI -> (SrcType.reg, SrcType.imm),
    InstrR -> (SrcType.reg, SrcType.reg),
    InstrS -> (SrcType.reg, SrcType.reg),
    InstrSA-> (SrcType.reg, SrcType.reg),
    InstrB -> (SrcType.reg, SrcType.reg),
    InstrU -> (SrcType.pc , SrcType.imm),
    InstrJ -> (SrcType.pc , SrcType.imm),
    InstrN -> (SrcType.pc , SrcType.imm)
  )
  val src1Type = LookupTree(instrType, SrcTypeTable.map(p => (p._1, p._2._1)))
  val src2Type = LookupTree(instrType, SrcTypeTable.map(p => (p._1, p._2._2)))

  val (rs, rt, rd) = (instr(19, 15), instr(24, 20), instr(11, 7))
  val rs1       = instr(11,7)
  val rs2       = instr(6,2)

  val rfSrc1 = rs
  val rfSrc2 = rt
  val rfDest = rd
  // TODO: refactor decode logic
  // make non-register addressing to zero, since isu.sb.isBusy(0) === false.B
  io.out.bits.ctrl.rfSrc1 := Mux(src1Type === SrcType.pc, 0.U, rfSrc1)
  io.out.bits.ctrl.rfSrc2 := Mux(src2Type === SrcType.reg, rfSrc2, 0.U)
  io.out.bits.ctrl.rfWen  := isrfWen(instrType)
  io.out.bits.ctrl.rfDest := Mux(isrfWen(instrType), rfDest, 0.U)

  io.out.bits.data := DontCare
  val imm = LookupTree(instrType, List(
    InstrI  -> SignExt(instr(31, 20), XLEN),
    InstrS  -> SignExt(Cat(instr(31, 25), instr(11, 7)), XLEN),
    InstrSA -> SignExt(Cat(instr(31, 25), instr(11, 7)), XLEN),
    InstrB  -> SignExt(Cat(instr(31), instr(7), instr(30, 25), instr(11, 8), 0.U(1.W)), XLEN),
    InstrU  -> SignExt(Cat(instr(31, 12), 0.U(12.W)), XLEN),//fixed
    InstrJ  -> SignExt(Cat(instr(31), instr(19, 12), instr(20), instr(30, 21), 0.U(1.W)), XLEN)
  ))
  io.out.bits.data.imm  := imm

  when (fuType === FuType.bru) {
    def isLink(reg: UInt) = (reg === 1.U || reg === 5.U)
    when (isLink(rfDest) && fuOpType === ALUOpType.jal) { io.out.bits.ctrl.fuOpType := ALUOpType.call }
    when (fuOpType === ALUOpType.jalr) {
      when (isLink(rfSrc1)) { io.out.bits.ctrl.fuOpType := ALUOpType.ret }
      when (isLink(rfDest)) { io.out.bits.ctrl.fuOpType := ALUOpType.call }
    }
  }
  // fix LUI
  io.out.bits.ctrl.src1Type := Mux(instr(6,0) === "b0110111".U, SrcType.reg, src1Type)
  io.out.bits.ctrl.src2Type := src2Type

  val NoSpecList = Seq(
    FuType.csr
  )

  val BlockList = Seq(
    FuType.mou
  )

  io.out.bits.ctrl.isNutCoreTrap := (instr(31,0) === NutCoreTrap.TRAP) && io.in.valid
  io.out.bits.ctrl.noSpecExec := NoSpecList.map(j => io.out.bits.ctrl.fuType === j).reduce(_ || _)
  io.out.bits.ctrl.isBlocked :=
  (
    io.out.bits.ctrl.fuType === FuType.lsu && LSUOpType.isAtom(io.out.bits.ctrl.fuOpType) ||
    BlockList.map(j => io.out.bits.ctrl.fuType === j).reduce(_ || _)
  )

  //output signals
  io.out.valid := io.in.valid
  io.in.ready := !io.in.valid || io.out.fire()
  io.out.bits.cf <> io.in.bits
  // fix c_break


  Debug(io.out.fire(), "issue: pc %x npc %x instr %x\n", io.out.bits.cf.pc, io.out.bits.cf.pnpc, io.out.bits.cf.instr)

  val intrVec = WireInit(0.U(12.W))
  BoringUtils.addSink(intrVec, "intrVecIDU")
  io.out.bits.cf.intrVec.zip(intrVec.asBools).map{ case(x, y) => x := y }
  hasIntr := intrVec.orR

  val vmEnable = WireInit(false.B)
  BoringUtils.addSink(vmEnable, "vmEnable")

  io.out.bits.cf.exceptionVec.map(_ := false.B)
  val is_sfence_vma = fuType === FuType.mou && fuOpType === MOUOpType.sfence_vma
  val sfence_vma_illegal = is_sfence_vma && io.sfence_vma_invalid
  val wfi_illegal = io.isWFI && io.wfi_invalid
  val illegal_instr = instrType === InstrN || isIllegalRVC || sfence_vma_illegal || wfi_illegal
  io.out.bits.cf.exceptionVec(illegalInstr) := illegal_instr && !hasIntr && io.in.valid
  io.out.bits.cf.exceptionVec(instrPageFault) := io.in.bits.exceptionVec(instrPageFault)
  io.out.bits.cf.exceptionVec(instrAccessFault) := io.in.bits.exceptionVec(instrAccessFault)

  io.out.bits.ctrl.isNutCoreTrap := (instr === NutCoreTrap.TRAP) && io.in.valid
  io.isWFI := (instr === Priviledged.WFI) && io.in.valid
  io.isBranch := VecInit(RV32I_BRUInstr.table.map(i => i._2.tail(1) === fuOpType)).asUInt.orR && fuType === FuType.bru

  // instruction coverage
  val enableInstrCoverage = true
  if (enableInstrCoverage) {
    // val c = Module(new CoverInstr(Instructions.DecodeTable.map(_._1)))
    // c.cover(io.out.fire, instr)
  }

  // instruction-imm coverage
  val enableInstrImmCoverage = true
  if (enableInstrImmCoverage) {
    // val c = Module(new CoverInstrImm(Instructions.DecodeTable))
    // c.cover(io.out.fire, instr)
  }
}

class IDU(implicit val p: NutCoreConfig) extends NutCoreModule with HasInstrType {
  val io = IO(new Bundle {
    val in = Vec(2, Flipped(Decoupled(new CtrlFlowIO)))
    val out = Vec(2, Decoupled(new DecodeIO))
    val sfence_vma_invalid = Input(Bool())
    val wfi_invalid = Input(Bool())
  })
  val decoder = Module(new Decoder)
  decoder.io.sfence_vma_invalid := io.sfence_vma_invalid
  decoder.io.wfi_invalid := io.wfi_invalid
  io.in(0) <> decoder.io.in
  io.out(0) <> decoder.io.out
  val isWFI = WireInit(decoder.io.isWFI)

  if (EnableMultiIssue) {
    val decoder2 = Module(new Decoder)
    io.in(1) <> decoder2.io.in
    io.out(1) <> decoder2.io.out
    isWFI := decoder.io.isWFI | decoder2.io.isWFI
  }
  else {
    io.in(1).ready := false.B
    io.out(1).valid := false.B
    io.out(1).bits := DontCare
  }

  io.out(0).bits.cf.isBranch := decoder.io.isBranch
  io.out(0).bits.cf.isExit := false.B
  io.out(0).bits.cf.runahead_checkpoint_id := 0.U
  // when(runahead.io.valid) {
  //   printf("fire pc %x branch %x inst %x\n", runahead.io.pc, runahead.io.branch, io.out(0).bits.cf.instr)
  // }

  if (!p.FPGAPlatform) {
    BoringUtils.addSource(isWFI, "isWFI")
  }
}
