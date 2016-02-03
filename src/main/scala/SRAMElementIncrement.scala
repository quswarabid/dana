package dana

import Chisel._

// SRAMElement variant that allows for element _and_ block writes with
// the option of writing a block that is accumualted with the elements
// of the existing block. Forwarding is allowable for all cases.

// This uses both write enable (we) and write type (wType) input
// lines. The write type is defined as follows:
//   0: element write (like SRAMElement0
//   1: block write overwriting old block
//   2: block write accumulating element-wise with old block

class SRAMElementIncrementInterface (
  override val dataWidth: Int,
  override val sramDepth: Int,
  override val numPorts: Int,
  override val elementWidth: Int
) extends SRAMElementInterface(dataWidth, sramDepth, numPorts, elementWidth) {
  override def cloneType = new SRAMElementIncrementInterface(
    dataWidth = dataWidth,
    sramDepth = sramDepth,
    numPorts = numPorts,
    elementWidth = elementWidth).asInstanceOf[this.type]
  val wType = Vec.fill(numPorts){ UInt(OUTPUT, width = log2Up(3)) }
}

class WritePendingIncrementBundle (
  val elementWidth: Int,
  val dataWidth: Int,
  val sramDepth: Int
) extends Bundle {
  override def cloneType = new WritePendingIncrementBundle (
    elementWidth = elementWidth,
    dataWidth = dataWidth,
    sramDepth = sramDepth).asInstanceOf[this.type]
  val valid = Bool()
  val wType = UInt(width = log2Up(3))
  val dataElement = UInt(width = elementWidth)
  val dataBlock = UInt(width = dataWidth)
  val addrHi = UInt(width = log2Up(sramDepth))
  val addrLo = UInt(width = log2Up(dataWidth / elementWidth))
}

// A special instance of the generic SRAM that allows for masked
// writes to the SRAM. Reads happen normally, but writes happen using
// a 2-cyle read-modify-write operation. Due to the nature of this
// operation, each write port needs an associated read port.
// Consequently, this only has RW ports.
class SRAMElementIncrement (
  override val dataWidth: Int = 32,
  override val sramDepth: Int = 64,
  override val numPorts: Int = 1,
  val elementWidth: Int = 8
) extends SRAMVariant {
  override lazy val io = new SRAMElementIncrementInterface(
    dataWidth = dataWidth,
    sramDepth = sramDepth,
    numPorts = numPorts,
    elementWidth = elementWidth
  ).flip

  val elementsPerBlock = divUp(dataWidth, elementWidth)

  val addr = Vec.fill(numPorts){ new Bundle{
    // [TODO] Use of Wire inside Vec may be verboten
    val addrHi = Wire(UInt(width = log2Up(sramDepth)))
    val addrLo = Wire(UInt(width = log2Up(elementsPerBlock)))}}

  val writePending = Vec.fill(numPorts){Reg(new WritePendingIncrementBundle(
    elementWidth = elementWidth,
    dataWidth = dataWidth,
    sramDepth = sramDepth))}

  val tmp = Wire(Vec.fill(numPorts){
    Vec.fill(elementsPerBlock){ UInt(width = elementWidth) }})
  val forwarding = Wire(Vec.fill(numPorts){ Bool() })

  def writeBlock(a: Vec[UInt], b: UInt) {
    (0 until elementsPerBlock).map(j =>
      a(j) := b(elementWidth*(j+1) - 1, elementWidth * j)) }

  def writeBlockIncrement(a: Vec[UInt], b: UInt, c: UInt) {
    (0 until elementsPerBlock).map(j =>
      a(j) := b((j+1) * elementWidth - 1, j * elementWidth) +
        c((j+1) * elementWidth - 1, j * elementWidth)) }

  // Combinational Logic
  for (i <- 0 until numPorts) {
    // Assign the addresses
    addr(i).addrHi := io.addr(i).toBits()(
      log2Up(sramDepth * elementsPerBlock) - 1,
      log2Up(elementsPerBlock))
    addr(i).addrLo := io.addr(i).toBits()(
      log2Up(elementsPerBlock) - 1, 0)
    // Connections to the sram
    sram.io.weW(i) := writePending(i).valid
    sram.io.dinW(i) := tmp(i).toBits()
    sram.io.addrR(i) := addr(i).addrHi
    io.dout(i) := sram.io.doutR(i)
    // Defaults
    forwarding(i) := Bool(false)
    (0 until elementsPerBlock).map(j =>
      tmp(i)(j) := sram.io.doutR(i)(elementWidth*(j+1)-1,elementWidth*j))
    sram.io.addrW(i) := writePending(i).addrHi

    when (writePending(i).valid) {
      val sameAddrHi = addr(i).addrHi === writePending(i).addrHi && io.we(i)
      val fwdElement = sameAddrHi && io.wType(i) === UInt(0)
      val fwdBlock = sameAddrHi && io.wType(i) === UInt(1)
      val fwdBlockIncrement = sameAddrHi && io.wType(i) === UInt(2)
      forwarding(i) := fwdElement | fwdBlock | fwdBlockIncrement
      switch (writePending(i).wType) {
        // Element Write
        is (UInt(0)) {
          forwarding(i) := sameAddrHi && io.wType(i) === UInt(0)
          for (j <- 0 until elementsPerBlock) {
            when (UInt(j) === writePending(i).addrLo) {
              tmp(i)(j) := writePending(i).dataElement }
            when (fwdElement &&  UInt(j) === addr(i).addrLo) {
              tmp(i)(j) := io.dinElement(i) }}
        }
        // Block Write
        is (UInt(1)) {
          writeBlock(tmp(i), writePending(i).dataBlock)
          when (fwdBlock) {
            writeBlock(tmp(i), io.din(i)) }}
        // Block Write with Element-wise Increment
        is (UInt(2)) {
          writeBlockIncrement(tmp(i), sram.io.doutR(i), writePending(i).dataBlock)
          when (fwdBlockIncrement) {
            (0 until elementsPerBlock).map(j =>
              tmp(i)(j) := io.din(i)(elementWidth*(j+1) - 1,
                elementWidth * j) +
                writePending(i).dataBlock(elementWidth*(j+1) - 1,
                  elementWidth * j) +
                sram.io.doutR(i).toBits()((j+1) * elementWidth - 1,
                  j * elementWidth)) }}}
      printf("[INFO] SRAMElementIncrement: PE write block Addr/Data_acc/Data_new/Data_old 0x%x/0x%x/0x%x/0x%x\n",
              writePending(i).addrHi##writePending(i).addrLo, tmp(i).toBits, writePending(i).dataBlock, sram.io.doutR(i).toBits)
    }
  }

  // Sequential Logic
  for (i <- 0 until numPorts) {
    // Assign the pending write data
    writePending(i).valid := Bool(false)
    when ((io.we(i)) && (forwarding(i) === Bool(false))) {
      writePending(i).valid := Bool(true)
      writePending(i).wType := io.wType(i)
      writePending(i).dataElement := io.dinElement(i)
      writePending(i).dataBlock := io.din(i)
      writePending(i).addrHi := addr(i).addrHi
      writePending(i).addrLo := addr(i).addrLo
    }
  }

  // Assertions

  // Any consecutive writes should be of the same type or else
  // behavior is technically undefined.
  assert(!Vec((0 until numPorts).map(
    i => writePending(i).valid && io.we(i) &&
      (addr(i).addrHi === writePending(i).addrHi) &&
      (writePending(i).wType =/= io.wType(i)))).contains(Bool(true)),
    "SRAMElementIncrement saw consecutive writes of different types")

  // We only define write types up through 2
  assert(!Vec((0 until numPorts).map(i =>
    io.we(i) && io.wType(i) > UInt(2))).contains(Bool(true)),
    "SRAMElementIncrement saw unsupported wType > 2")
}
