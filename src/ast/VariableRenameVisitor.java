package ast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class VariableRenameVisitor implements Visitor {
    private String oldName;
    private String newName;
    private int lineNumber;

    private SymbolTable programST;
    /**
     * To find descendants who inherit the field and reference
     * it in their methods
     */
    private InheritanceForest forest;

    public VariableRenameVisitor(String oldName, String newName, int lineNumber) {
        this.oldName = oldName;
        this.newName = newName;
        this.lineNumber = lineNumber;
    }

    @Override
    public void visit(Program program) {
        forest = new InheritanceForest(program);
        programST = new SymbolTable(program);
        /**
         * first find all suspected change locations
         */
        for (ClassDecl classDecl : program.classDecls()) {
            SymbolTable classST = programST.getSymbol(classDecl.name(), false).enclosedScope();
            /**
             * Case 1: the var we change is a field in a class.
             * Then we need to look at all the descendants of ths declaring class
             * and change all references to this field.
             */
            if (classST.contains(oldName, false) && classST.getSymbol(oldName, false).declaration().lineNumber == lineNumber) {
                // List of class declaration which may (or may not) require changes during rename
                List<ClassDecl> containingClasses = forest.getDescendants(classDecl);
                if (containingClasses == null) {
                    containingClasses = new ArrayList<ClassDecl>();
                }
                containingClasses.add(classDecl);
                for (ClassDecl cls : containingClasses) {
                    cls.accept(this);
                }
                return;
            }
            /**
             * Case 2: the var we need to change is a local variable in a method.
             * This case is easy because the change will only affect the method's scope.
             * After that we can return because no other visits are required to complete
             * this change
             */
            List<MethodDecl> classMethods = classDecl.methoddecls();
            for (MethodDecl method : classMethods) {
                SymbolTable methodST = classST.getSymbol(method.name(), true).enclosedScope();
                if (methodST.contains(oldName, false) && methodST.getSymbol(oldName, false).declaration().lineNumber == lineNumber) {
                    method.accept(this);
                    return;
                }
            }
        }

    }

    /**
     * Since we are only going to visit classes that are suspected to inherit
     * or declare the field we rename, we need to check that
     * a. The class doesn't hide this field by declaring its own under the same name
     * b. The class methods referring to the field don't declare their own local vars under
     * that same name
     */
    @Override
    public void visit(ClassDecl classDecl) {
        SymbolTable classST = programST.getSymbol(classDecl.name(), false).enclosedScope();
        if (classST.contains(oldName, false)) {
            STSymbol fieldSymbol = classST.getSymbol(oldName, false);
            /**
             * if class declares this field, and its line number doesn't match the search query,
             * we conclude it's a descendant class of the original declarator which is hiding the field it inherited
             * by another field with the same name. In that case it should not be renamed. Hence the only
             * case where we would like to proceed down the AST is the case where this is the original declarator.
             */
            if (fieldSymbol.kind() == STSymbol.SymbolKind.FIELD && fieldSymbol.declaration().lineNumber == lineNumber) {
                VarDecl fieldDecl = (VarDecl) classST.getSymbol(oldName, false).declaration();
                fieldDecl.accept(this);
                for (MethodDecl methodDecl: classDecl.methoddecls()) {
                    methodDecl.accept(this);
                }
            }
        } else {
            for (MethodDecl methodDecl: classDecl.methoddecls()) {
                methodDecl.accept(this);
            }
        }
    }

    @Override
    public void visit(MainClass mainClass) {
        /**
         * No variables in this class so this can only be a method call
         * or something that doesn't have to do with the change target
         */
        return;
    }

    private void visit(Expr e){
        String classTypeName = e.getClass().getName();
        String binars[] = {"ast.AddExpr", "ast.AndExpr", "ast.LtExpr", "ast.MultExpr", "ast.SubtractExpr"};
        if(Arrays.asList(binars).contains(classTypeName)){
            ((BinaryExpr)e).accept(this);
            return;
        }
        switch(classTypeName){
            case "ast.ArrayAccessExpr":
                ((ArrayAccessExpr)e).accept(this);
                return;
            case "ast.ArrayLengthExpr":
                ((ArrayLengthExpr)e).accept(this);
                return;
            case "ast.MethodCallExpr":
                ((MethodCallExpr)e).accept(this);
                return;
            case "ast.NotExpr":
                ((NotExpr)e).accept(this);
                return;
            case "ast.NewIntArrayExpr":
                ((NewIntArrayExpr)e).accept(this);
                return;
            case "ast.IdentifierExpr":
                ((IdentifierExpr)e).accept(this);
                return;

            default: //????
        }
    }

    @Override
    public void visit(MethodDecl methodDecl) {
        SymbolTable declaringClassST = methodDecl.enclosingScope();
        SymbolTable methodST = declaringClassST.getSymbol(methodDecl.name(), true).enclosedScope();
        if (
                (methodST.contains(oldName, false) && methodST.getSymbol(oldName, false).declaration().lineNumber == lineNumber)
                || !methodST.contains(oldName, false)
        ) {
            if (methodST.contains(oldName, false)) {
                VariableIntroduction targetDecl = (VariableIntroduction) methodST.getSymbol(oldName, false).declaration();
                targetDecl.accept(this);
            }
            List<Statement> body = methodDecl.body();
            for (Statement stmt : body) {
                stmt.accept(this);
            }
            this.visit(methodDecl.ret());
        }
    }


    @Override
    public void visit(VarDecl varDecl) {
        if (varDecl.name().equals(oldName) && varDecl.lineNumber == lineNumber) {
            varDecl.setName(newName);
        }
    }


    @Override
    public void visit(FormalArg formalArg) {
        if (formalArg.name().equals(oldName) && formalArg.lineNumber == lineNumber) {
            formalArg.setName(newName);
        }
    }


    @Override
    public void visit(BlockStatement blockStatement) {
        List<Statement> statements = blockStatement.statements();
        for (Statement stmt : statements) {
            stmt.accept(this);
        }
    }

    @Override
    public void visit(IfStatement ifStatement) {
        ifStatement.cond().accept(this);
        ifStatement.thencase().accept(this);
        ifStatement.elsecase().accept(this);
    }

    @Override
    public void visit(WhileStatement whileStatement) {
        whileStatement.cond().accept(this);
        whileStatement.body().accept(this);
    }

    @Override
    public void visit(SysoutStatement sysoutStatement) {
        sysoutStatement.arg().accept(this);
    }

    @Override
    public void visit(AssignStatement assignStatement) {
        if (assignStatement.lv().equals(oldName)) {
            assignStatement.setLv(newName);
        }
        assignStatement.rv().accept(this);
    }

    @Override
    public void visit(AssignArrayStatement assignArrayStatement) {
        /**
         * all this is under the assumption that the ast represents a
         * program that compiles...
         */
        if (assignArrayStatement.lv().equals(oldName)) {
            assignArrayStatement.setLv(newName);
        }
        assignArrayStatement.index().accept(this);
        assignArrayStatement.rv().accept(this);
    }

    private void visit(BinaryExpr bnexpr) {
        bnexpr.e1().accept(this);
        bnexpr.e2().accept(this);
    }

    @Override
    public void visit(AndExpr e) {
        visit((BinaryExpr) e);
    }

    @Override
    public void visit(LtExpr e) {
        visit((BinaryExpr) e);
    }

    @Override
    public void visit(AddExpr e) {
        visit((BinaryExpr) e);
    }

    @Override
    public void visit(SubtractExpr e) {
        visit((BinaryExpr) e);
    }

    @Override
    public void visit(MultExpr e) {
        visit((BinaryExpr) e);
    }

    @Override
    public void visit(ArrayAccessExpr e) {
        e.arrayExpr().accept(this);
        e.indexExpr().accept(this);
    }

    @Override
    public void visit(ArrayLengthExpr e) {
        e.arrayExpr().accept(this);
    }

    @Override
    public void visit(MethodCallExpr e) {
        e.ownerExpr().accept(this);
        for(Expr expr: e.actuals()) {
            expr.accept(this);
        };
    }

    @Override
    public void visit(IntegerLiteralExpr e) {
        return;
    }

    @Override
    public void visit(TrueExpr e) {
        return;
    }

    @Override
    public void visit(FalseExpr e) {
        return;
    }

    @Override
    public void visit(IdentifierExpr e) {
        if (e.id().equals(oldName)) {
            e.setId(newName);
        }
    }

    @Override
    public void visit(ThisExpr e) {
        return;
    }

    @Override
    public void visit(NewIntArrayExpr e) {

        e.lengthExpr().accept(this);
    }

    @Override
    public void visit(NewObjectExpr e) {
        return;
    }

    @Override
    public void visit(NotExpr e) {
        e.e().accept(this);
    }

    @Override
    public void visit(IntAstType t) {
        return;
    }

    @Override
    public void visit(BoolAstType t) {
        return;
    }

    @Override
    public void visit(IntArrayAstType t) {
        return;
    }

    @Override
    public void visit(RefType t) {
        if (t.id().equals(oldName)) {
            t.setId(newName);
        }
    }
};
