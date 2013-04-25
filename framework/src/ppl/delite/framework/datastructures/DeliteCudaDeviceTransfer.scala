package ppl.delite.framework.datastructures

import scala.lms.internal.{GenerationFailedException, Expressions, CudaDeviceTransfer, CudaCodegen}
import scala.lms.ops.BaseGenStruct


trait DeliteCudaDeviceTransfer extends CudaDeviceTransfer {
  this: CudaCodegen with BaseGenStruct =>

  val IR: Expressions
  import IR._

  private def isVarType[T](m: Manifest[T]) = m.erasure.getSimpleName == "Variable"
  private def baseType[T](m: Manifest[T]) = if (isVarType(m)) mtype(m.typeArguments(0)) else m
  
  override def emitSendSlave(tp: Manifest[Any]): (String,String) = {
    if (tp.erasure == classOf[Variable[AnyVal]]) {
      val out = new StringBuilder
      val typeArg = tp.typeArguments.head
      if (!isPrimitiveType(typeArg)) throw new GenerationFailedException("emitSend Failed") //TODO: Enable non-primitie type refs
      val signature = "Ref< %s > *sendCuda_Ref__%s__(HostRef< %s > *%s)".format(remap(typeArg),mangledName(remap(tp)),remap(typeArg),"sym")
      out.append(signature + " {\n")
      out.append("\tRef< %s > *%s_dev = new Ref< %s >(%s->get());\n".format(remap(typeArg),"sym",remap(typeArg),"sym"))
      out.append("\treturn %s_dev;\n".format("sym"))
      out.append("}\n")
      (signature+";\n", out.toString)
    }
    else if(encounteredStructs.contains(structName(tp))) {
      val out = new StringBuilder
      val signature = "%s *sendCuda_%s(Host%s *%s)".format(remap(tp),mangledName(remap(tp)),remap(tp),"sym")
      out.append(signature + " {\n")
      out.append("\t%s *%s_dev = new %s();\n".format(remap(tp),"sym",remap(tp)))
      for(elem <- encounteredStructs(structName(tp))) {
        val elemtp = baseType(elem._2)
        if(isPrimitiveType(elemtp)) {
          out.append("\t%s_dev->%s = %s->%s;\n".format("sym",elem._1,"sym",elem._1))
        }
        else {
          out.append("\t%s_dev->%s = *sendCuda_%s(&(%s->%s));\n".format("sym",elem._1,mangledName(remap(elemtp)),"sym",elem._1))
        }
      }
      out.append("\treturn %s_dev;\n".format("sym"))
      out.append("}\n")
      (signature+";\n", out.toString)
    }
    else if(remap(tp).startsWith("DeliteArray<")) {
      remap(tp) match {
        case "DeliteArray< bool >" | "DeliteArray< char >" | "DeliteArray< CHAR >" | "DeliteArray< short >" | "DeliteArray< int >" | "DeiteArray< long >" | "DeliteArray< float >" | "DeliteArray< double >" =>
          val out = new StringBuilder
          val typeArg = tp.typeArguments.head
          val signature = "%s *sendCuda_%s(Host%s *%s)".format(remap(tp),mangledName(remap(tp)),remap(tp),"sym")
          out.append(signature + " {\n")
          out.append("\t%s *hostPtr;\n".format(remap(typeArg)))
          out.append("\tDeliteCudaMallocHost((void**)&hostPtr,%s->length*sizeof(%s));\n".format("sym",remap(typeArg)))
          out.append("\tmemcpy(hostPtr, %s->data, %s->length*sizeof(%s));\n".format("sym","sym",remap(typeArg)))
          out.append("\t%s *%s_dev = new %s(%s->length);\n".format(remap(tp),"sym",remap(tp),"sym"))
          out.append("\tDeliteCudaMemcpyHtoDAsync(%s_dev->data, hostPtr, %s->length*sizeof(%s));\n".format("sym","sym",remap(typeArg)))
          out.append("\treturn %s_dev;\n".format("sym"))
          out.append("}\n")
          val signatureT = "%s *sendCudaTrans_%s(Host%s *%s, int stride)".format(remap(tp),mangledName(remap(tp)),remap(tp),"sym")
          out.append(signatureT + " {\n")
          out.append("\t%s *hostPtr;\n".format(remap(typeArg)))
          out.append("\tDeliteCudaMallocHost((void**)&hostPtr,%s->length*sizeof(%s));\n".format("sym",remap(typeArg)))
          out.append("\tint numCols = stride;\n")
          out.append("\tint numRows = sym->length / stride;\n")
          out.append("\tfor(int i=0; i<numRows; i++) {\n")
          out.append("\t\tfor(int j=0; j<numCols; j++) {\n")
          out.append("\t\t\thostPtr[j*numRows+i] = sym->data[i*numCols+j];\n")
          out.append("\t\t}\n")
          out.append("\t}\n")
          out.append("\t%s *%s_dev = new %s(%s->length);\n".format(remap(tp),"sym",remap(tp),"sym"))
          out.append("\tsym_dev->offset = 0; sym_dev->stride = numRows; sym_dev->flag = numCols;\n")
          out.append("\tDeliteCudaMemcpyHtoDAsync(%s_dev->data, hostPtr, %s->length*sizeof(%s));\n".format("sym","sym",remap(typeArg)))
          out.append("\treturn %s_dev;\n".format("sym"))
          out.append("}\n")
          (signature+";\n" + signatureT+";\n", out.toString)
        case _ => 
          val out = new StringBuilder
          val typeArg = tp.typeArguments.head
          val signature = "%s *sendCuda_%s(Host%s *%s)".format(remap(tp),mangledName(remap(tp)),remap(tp),"sym")
          out.append(signature + " {\n")
          out.append("assert(false);\n")
          out.append("}\n")
          (signature+";\n", out.toString)
      }
    }
    else
      super.emitSendSlave(tp)
  }

  override def emitRecvSlave(tp: Manifest[Any]): (String,String) = {
    if (tp.erasure == classOf[Variable[AnyVal]]) {
      val out = new StringBuilder
      val typeArg = tp.typeArguments.head
      if (!isPrimitiveType(typeArg)) throw new GenerationFailedException("emitSend Failed") //TODO: Enable non-primitie type refs
      val signature = "HostRef< %s > *recvCuda_Ref__%s__(Ref< %s > *%s_dev)".format(remap(typeArg),mangledName(remap(tp)),remap(typeArg),"sym")
      out.append(signature + " {\n")
      out.append("assert(false);\n")
      out.append("}\n")
      (signature+";\n", out.toString)
    }
    else if(encounteredStructs.contains(structName(tp))) {
      val out = new StringBuilder
      val signature = "Host%s *recvCuda_%s(%s *%s_dev)".format(remap(tp),mangledName(remap(tp)),remap(tp),"sym")
      out.append(signature + " {\n")
      out.append("\tHost%s *%s = new Host%s();\n".format(remap(tp),"sym",remap(tp)))
      for(elem <- encounteredStructs(structName(tp))) {
        val elemtp = baseType(elem._2)
        if(isPrimitiveType(elemtp)) {
          out.append("\t%s->%s = %s_dev->%s;\n".format("sym",elem._1,"sym",elem._1))
        }
        else {
          out.append("\t%s->%s = *recvCuda_%s(&(%s_dev->%s));\n".format("sym",elem._1,mangledName(remap(elemtp)),"sym",elem._1))
        }
      }
      out.append("\treturn %s;\n".format("sym"))
      out.append("}\n")
      (signature+";\n", out.toString)
    }
    else if(remap(tp).startsWith("DeliteArray<")) {
      remap(tp) match {
        case "DeliteArray< bool >" | "DeliteArray< char >" | "DeliteArray< CHAR >" | "DeliteArray< short >" | "DeliteArray< int >" | "DeiteArray< long >" | "DeliteArray< float >" | "DeliteArray< double >" =>
          val out = new StringBuilder
          val typeArg = tp.typeArguments.head
          val signature = "Host%s *recvCuda_%s(%s *%s_dev)".format(remap(tp),mangledName(remap(tp)),remap(tp),"sym")
          out.append(signature + " {\n")
          out.append("\t%s *hostPtr;\n".format(remap(typeArg)))
          out.append("\tDeliteCudaMallocHost((void**)&hostPtr,%s_dev->length*sizeof(%s));\n".format("sym",remap(typeArg)))
          out.append("\tDeliteCudaMemcpyDtoHAsync(hostPtr, %s_dev->data, %s_dev->length*sizeof(%s));\n".format("sym","sym",remap(typeArg)))
          out.append("\tHost%s *%s = new Host%s(%s_dev->length);\n".format(remap(tp),"sym",remap(tp),"sym","sym"))
          out.append("\tmemcpy(%s->data,hostPtr,%s->length*sizeof(%s));\n".format("sym","sym",remap(typeArg)))
          out.append("\treturn %s;\n".format("sym"))
          out.append("}\n")
          (signature+";\n", out.toString)
        case _ => // DeliteArrayObject
          val out = new StringBuilder
          val typeArg = tp.typeArguments.head
          val signature = "HostDeliteArray< Host%s > *recvCuda_%s(%s *%s_dev)".format(remap(typeArg),mangledName(remap(tp)),remap(tp),"sym")
          out.append(signature + " {\n")
          out.append("\tHostDeliteArray< Host%s > *res = new HostDeliteArray< Host%s >(sym_dev->length);\n".format(remap(typeArg),remap(typeArg)))
          out.append("\t%s *temp = (%s*)malloc(sizeof(%s)*sym_dev->length);\n".format(remap(typeArg),remap(typeArg),remap(typeArg)))
          out.append("\tDeliteCudaMemcpyDtoHAsync((void*)temp, (void*)(sym_dev->data), sym_dev->length*sizeof(%s));\n".format(remap(typeArg)))
          out.append("\tres->length = sym_dev->length;\n")
          out.append("\tfor(int i=0; i<res->length; i++) {\n")
          out.append("\t\tres->data[i] = *recvCuda_%s(temp+i);\n".format(remap(typeArg)))
          out.append("\t}\n")
          out.append("\treturn res;\n")
          out.append("}\n")
          (signature+";\n", out.toString)
      }
    }
    else
      super.emitRecvSlave(tp)
  }

  /*
  override def emitSendViewSlave(sym: Sym[Any]): (String,String) = {
  }
  override def emitRecvViewSlave(sym: Sym[Any]): (String,String) = {
  }
  */

  override def emitSendUpdateSlave(tp: Manifest[Any]): (String,String) = {
    if (tp.erasure == classOf[Variable[AnyVal]]) {
      val out = new StringBuilder
      val typeArg = tp.typeArguments.head
      if (!isPrimitiveType(typeArg)) throw new GenerationFailedException("emitSend Failed") //TODO: Enable non-primitie type refs
      val signature = "void sendUpdateCuda_Ref__%s__(Ref< %s > *%s_dev, HostRef< %s > *%s)".format(mangledName(remap(tp)),remap(typeArg),"sym",remap(typeArg),"sym")
      out.append(signature + " {\n")
      out.append("assert(false);\n")
      out.append("}\n")
      (signature+";\n", out.toString)
    }
    else if(encounteredStructs.contains(structName(tp))) {
      val out = new StringBuilder
      val signature = "void sendUpdateCuda_%s(%s *%s_dev, Host%s *%s)".format(mangledName(remap(tp)),remap(tp),"sym",remap(tp),"sym")
      out.append(signature + " {\n")
      out.append("assert(false);\n")
      out.append("}\n")
      (signature+";\n", out.toString)
    }
    else if(remap(tp).startsWith("DeliteArray<")) {
      remap(tp) match {
       case "DeliteArray< bool >" | "DeliteArray< char >" | "DeliteArray< CHAR >" | "DeliteArray< short >" | "DeliteArray< int >" | "DeiteArray< long >" | "DeliteArray< float >" | "DeliteArray< double >" =>
          val out = new StringBuilder
          val typeArg = tp.typeArguments.head
          val signature = "void sendUpdateCuda_%s(%s *%s_dev, Host%s *%s)".format(mangledName(remap(tp)),remap(tp),"sym",remap(tp),"sym")
          out.append(signature + " {\n")
          out.append("\t%s *hostPtr;\n".format(remap(typeArg)))
          out.append("\tDeliteCudaMallocHost((void**)&hostPtr,%s->length*sizeof(%s));\n".format("sym",remap(typeArg)))
          out.append("\tmemcpy(hostPtr, %s->data, %s->length*sizeof(%s));\n".format("sym","sym",remap(typeArg)))
          out.append("\tDeliteCudaMemcpyHtoDAsync(%s_dev->data, hostPtr, %s->length*sizeof(%s));\n".format("sym","sym",remap(typeArg)))
          out.append("}\n")
          (signature+";\n", out.toString)
        case _ => 
          ("", "") //TODO
      }
    }
    else
      super.emitSendUpdateSlave(tp)
  }

  override def emitRecvUpdateSlave(tp: Manifest[Any]): (String,String) = {
    if (tp.erasure == classOf[Variable[AnyVal]]) {
      val out = new StringBuilder
      val typeArg = tp.typeArguments.head
      if (!isPrimitiveType(typeArg)) throw new GenerationFailedException("emitSend Failed") //TODO: Enable non-primitie type refs
      val signature = "void recvUpdateCuda_Ref__%s__(Ref< %s > *%s_dev, HostRef< %s > *%s)".format(mangledName(remap(tp)),remap(typeArg),"sym",remap(typeArg),"sym")
      out.append(signature + " {\n")
      out.append("assert(false);\n")
      out.append("}\n")
      (signature+";\n", out.toString)
    }
    else if(encounteredStructs.contains(structName(tp))) {
      val out = new StringBuilder
      val signature = "void recvUpdateCuda_%s(%s *%s_dev, Host%s *%s)".format(mangledName(remap(tp)),remap(tp),"sym",remap(tp),"sym")
      out.append(signature + " {\n")
      for(elem <- encounteredStructs(structName(tp))) {
        val elemtp = baseType(elem._2)
        if(isPrimitiveType(elemtp)) {
          out.append("\tsym->%s = sym_dev->%s;\n".format(elem._1,elem._1))
        }
        else { // Always assume array type?
          out.append("\trecvUpdateCuda_%s(&(sym_dev->%s), &(sym->%s));\n".format(mangledName(remap(elemtp)),elem._1,elem._1))
        }
      }
      out.append("}\n")
      (signature+";\n", out.toString)
    }
    else if(remap(tp).startsWith("DeliteArray<")) {
      remap(tp) match {
        case "DeliteArray< bool >" | "DeliteArray< char >" | "DeliteArray< CHAR >" | "DeliteArray< short >" | "DeliteArray< int >" | "DeiteArray< long >" | "DeliteArray< float >" | "DeliteArray< double >" =>
          val out = new StringBuilder
          val typeArg = tp.typeArguments.head
          val signature = "void recvUpdateCuda_%s(%s *%s_dev, Host%s *%s)".format(mangledName(remap(tp)),remap(tp),"sym",remap(tp),"sym")
          out.append(signature + " {\n")
          out.append("\t%s *hostPtr;\n".format(remap(typeArg)))
          out.append("\tDeliteCudaMallocHost((void**)&hostPtr,%s->length*sizeof(%s));\n".format("sym",remap(typeArg)))
          out.append("\tDeliteCudaMemcpyDtoHAsync(hostPtr, %s_dev->data, %s->length*sizeof(%s));\n".format("sym","sym",remap(typeArg)))
          out.append("\tmemcpy(%s->data,hostPtr,%s->length*sizeof(%s));\n".format("sym","sym",remap(typeArg)))
          out.append("}\n")
          (signature+";\n", out.toString)
        case _ => 
          ("", "") //TODO
      }
    }
    else
      super.emitRecvUpdateSlave(tp)
  }

}
